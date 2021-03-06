/*
 * Copyright 2013-2019 Erudika. https://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.scoold.controllers;

import com.erudika.para.client.ParaClient;
import com.erudika.para.core.ParaObject;
import com.erudika.para.core.Sysprop;
import com.erudika.para.core.Webhook;
import com.erudika.para.utils.Config;
import com.erudika.para.utils.Pager;
import com.erudika.para.utils.Utils;
import static com.erudika.scoold.ScooldServer.ADMINLINK;
import static com.erudika.scoold.ScooldServer.HOMEPAGE;
import com.erudika.scoold.core.Profile;
import com.erudika.scoold.utils.ScooldUtils;
import com.typesafe.config.ConfigValue;
import java.util.Map;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.erudika.scoold.core.Question;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;

/**
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

	private static final Logger logger = LoggerFactory.getLogger(AdminController.class);
	private final String scooldVersion = getClass().getPackage().getImplementationVersion();
	private static final int MAX_SPACES = 10; // Hey! It's cool to edit this, but please consider buying Scoold Pro! :)

	private final ScooldUtils utils;
	private final ParaClient pc;

	@Inject
	public AdminController(ScooldUtils utils) {
		this.utils = utils;
		this.pc = utils.getParaClient();
	}

	@GetMapping
	public String get(HttpServletRequest req, Model model) {
		if (!utils.isAuthenticated(req) || !utils.isAdmin(utils.getAuthUser(req))) {
			return "redirect:" + HOMEPAGE;
		}
		Map<String, Object> configMap = new LinkedHashMap<String, Object>();
		for (Map.Entry<String, ConfigValue> entry : Config.getConfig().entrySet()) {
			ConfigValue value = entry.getValue();
			configMap.put(Config.PARA + "_" + entry.getKey(), value != null ? value.unwrapped() : "-");
		}
		configMap.putAll(System.getenv());

		Pager itemcount = utils.getPager("page", req);
		Pager itemcount1 = utils.getPager("page1", req);
		itemcount.setLimit(40);
		model.addAttribute("path", "admin.vm");
		model.addAttribute("title", utils.getLang(req).get("admin.title"));
		model.addAttribute("configMap", configMap);
		model.addAttribute("version", pc.getServerVersion());
		model.addAttribute("endpoint", pc.getEndpoint());
		model.addAttribute("paraapp", Config.getConfigParam("access_key", "x"));
		model.addAttribute("spaces", pc.findQuery("scooldspace", "*", itemcount));
		model.addAttribute("webhooks", pc.findQuery(Utils.type(Webhook.class), "*", itemcount1));
		model.addAttribute("coreScooldTypes", utils.getCoreScooldTypes());
		model.addAttribute("itemcount", itemcount);
		model.addAttribute("itemcount1", itemcount1);
		model.addAttribute("isDefaultSpacePublic", utils.isDefaultSpacePublic());
		model.addAttribute("scooldVersion", Optional.ofNullable(scooldVersion).orElse("unknown"));
		return "base";
	}

	@PostMapping("/add-space")
	public String addSpace(@RequestParam String space, HttpServletRequest req, HttpServletResponse res, Model model) {
		Profile authUser = utils.getAuthUser(req);
		if (!StringUtils.isBlank(space) && utils.isAdmin(authUser)) {
			Sysprop spaceObj = utils.buildSpaceObject(space);
			if (utils.isDefaultSpace(spaceObj.getId()) || pc.getCount("scooldspace") >= MAX_SPACES ||
					pc.read(spaceObj.getId()) != null) {
				if (utils.isAjaxRequest(req)) {
					res.setStatus(400);
					return "space";
				} else {
					return "redirect:" + ADMINLINK + "?code=7&error=true";
				}
			} else {
				if (pc.create(spaceObj) != null) {
					authUser.getSpaces().add(spaceObj.getId() + Config.SEPARATOR + spaceObj.getName());
					authUser.update();
					model.addAttribute("space", spaceObj);
				} else {
					model.addAttribute("error", Collections.singletonMap("name", utils.getLang(req).get("posts.error1")));
				}
			}
		} else {
			model.addAttribute("error", Collections.singletonMap("name", utils.getLang(req).get("requiredfield")));
		}
		if (utils.isAjaxRequest(req)) {
			res.setStatus(model.containsAttribute("error") ? 400 : 200);
			return "space";
		} else {
			return "redirect:" + ADMINLINK;
		}
	}

	@PostMapping("/remove-space")
	public String removeSpace(@RequestParam String space, HttpServletRequest req, HttpServletResponse res) {
		Profile authUser = utils.getAuthUser(req);
		if (!StringUtils.isBlank(space) && utils.isAdmin(authUser)) {
			pc.delete(new Sysprop(utils.getSpaceId(space)));
			authUser.getSpaces().remove(space);
			authUser.update();
		}
		if (utils.isAjaxRequest(req)) {
			res.setStatus(200);
			return "space";
		} else {
			return "redirect:" + ADMINLINK;
		}
	}

	@PostMapping("/create-webhook")
	public String createWebhook(@RequestParam String targetUrl, @RequestParam String type, @RequestParam Boolean json,
			@RequestParam Set<String> events, HttpServletRequest req, Model model) {
		Profile authUser = utils.getAuthUser(req);
		if (Utils.isValidURL(targetUrl) && utils.isAdmin(authUser) && utils.isWebhooksEnabled()) {
			Webhook webhook = new Webhook(targetUrl);
			webhook.setCreate(events.contains("create"));
			webhook.setUpdate(events.contains("update"));
			webhook.setDelete(events.contains("delete"));
			webhook.setCreateAll(events.contains("createAll"));
			webhook.setUpdateAll(events.contains("updateAll"));
			webhook.setDeleteAll(events.contains("deleteAll"));
			if (utils.getCoreScooldTypes().contains(type)) {
				webhook.setTypeFilter(type);
			} else {
				webhook.setTypeFilter(Utils.type(Question.class));
			}
			webhook.setUrlEncoded(!json);
			webhook.resetSecret();
			pc.create(webhook);
		} else {
			model.addAttribute("error", Collections.singletonMap("targetUrl", utils.getLang(req).get("requiredfield")));
			return "base";
		}
		return "redirect:" + ADMINLINK;
	}

	@PostMapping("/toggle-webhook")
	public String toggleWebhook(@RequestParam String id, HttpServletRequest req, HttpServletResponse res) {
		Profile authUser = utils.getAuthUser(req);
		if (!StringUtils.isBlank(id) && utils.isAdmin(authUser) && utils.isWebhooksEnabled()) {
			Webhook webhook = pc.read(id);
			if (webhook != null) {
				webhook.setActive(!webhook.getActive());
				pc.update(webhook);
			}
		}
		if (utils.isAjaxRequest(req)) {
			res.setStatus(200);
			return "base";
		} else {
			return "redirect:" + ADMINLINK;
		}
	}

	@PostMapping("/delete-webhook")
	public String deleteWebhook(@RequestParam String id, HttpServletRequest req, HttpServletResponse res) {
		Profile authUser = utils.getAuthUser(req);
		if (!StringUtils.isBlank(id) && utils.isAdmin(authUser) && utils.isWebhooksEnabled()) {
			Webhook webhook = new Webhook();
			webhook.setId(id);
			pc.delete(webhook);
		}
		if (utils.isAjaxRequest(req)) {
			res.setStatus(200);
			return "base";
		} else {
			return "redirect:" + ADMINLINK;
		}
	}

	@PostMapping
	public String forceDelete(@RequestParam Boolean confirmdelete, @RequestParam String id, HttpServletRequest req) {
		Profile authUser = utils.getAuthUser(req);
		if (confirmdelete && utils.isAdmin(authUser)) {
			ParaObject object = pc.read(id);
			if (object != null) {
				object.delete();
				logger.info("{} #{} deleted {} #{}", authUser.getName(), authUser.getId(),
						object.getClass().getName(), object.getId());
			}
		}
		return "redirect:" + Optional.ofNullable(req.getParameter("returnto")).orElse(ADMINLINK);
	}
}
