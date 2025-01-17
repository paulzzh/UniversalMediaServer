/*
 * This file is part of Universal Media Server, based on PS3 Media Server.
 *
 * This program is free software; you can redistribute it and/or
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; version 2 of the License only.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package net.pms.network.webguiserver.servlets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import net.pms.Messages;
import net.pms.PMS;
import net.pms.configuration.FormatConfiguration;
import net.pms.dlna.DLNAThumbnailInputStream;
import net.pms.encoders.EngineFactory;
import net.pms.encoders.FFmpegWebVideo;
import net.pms.encoders.HlsHelper;
import net.pms.encoders.ImageEngine;
import net.pms.encoders.StandardEngineId;
import net.pms.external.tmdb.TMDB;
import net.pms.formats.Format;
import net.pms.iam.Account;
import net.pms.iam.AuthService;
import net.pms.iam.Permissions;
import net.pms.image.BufferedImageFilterChain;
import net.pms.image.Image;
import net.pms.image.ImageFormat;
import net.pms.image.ImageInfo;
import net.pms.image.ImagesUtil;
import net.pms.io.OutputParams;
import net.pms.io.ProcessWrapper;
import net.pms.media.MediaInfo;
import net.pms.media.subtitle.MediaSubtitle;
import net.pms.media.video.metadata.MediaVideoMetadata;
import net.pms.media.video.metadata.TvSeriesMetadata;
import net.pms.network.HTTPResource;
import net.pms.network.webguiserver.GuiHttpServlet;
import net.pms.network.webguiserver.ServerSentEvents;
import net.pms.renderers.ConnectedRenderers;
import net.pms.renderers.Renderer;
import net.pms.renderers.devices.WebGuiRenderer;
import net.pms.renderers.devices.players.WebGuiPlayer;
import net.pms.store.StoreItem;
import net.pms.store.StoreResource;
import net.pms.store.container.CodeEnter;
import net.pms.store.container.MediaLibraryFolder;
import net.pms.store.container.MediaLibraryTvSeries;
import net.pms.store.container.TranscodeVirtualFolder;
import net.pms.store.item.DVDISOTitle;
import net.pms.store.item.MediaLibraryTvEpisode;
import net.pms.store.item.RealFile;
import net.pms.store.item.VirtualVideoAction;
import net.pms.util.ByteRange;
import net.pms.util.FileUtil;
import net.pms.util.FullyPlayed;
import net.pms.util.PropertiesUtil;
import net.pms.util.UMSUtils;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WebServlet(name = "PlayerApiServlet", urlPatterns = {"/v1/api/player"}, displayName = "Player Api Servlet")
public class PlayerApiServlet extends GuiHttpServlet {

	private static final Logger LOGGER = LoggerFactory.getLogger(PlayerApiServlet.class);

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		try {
			var path = req.getServletPath();
			if (path.equals("/")) {
				Account account = AuthService.getPlayerAccountLoggedIn(req);
				if (account == null) {
					respondUnauthorized(req, resp);
					return;
				}
				if (!account.havePermission(Permissions.WEB_PLAYER_BROWSE)) {
					respondForbidden(req, resp);
					return;
				}
				String uuid = ConnectedRenderers.getRandomUUID();
				respond(req, resp, "{\"uuid\":\"" + uuid + "\"}", 200, "application/json");
			} else if (path.startsWith("/sse/")) {
				String[] sseData = path.split("/");
				if (sseData.length == 3) {
					WebGuiRenderer renderer = getRenderer(req, sseData[2]);
					if (renderer != null && renderer.havePermission(Permissions.WEB_PLAYER_BROWSE)) {
						resp.setHeader("Server", PMS.get().getServerName());
						resp.setHeader("Connection", "keep-alive");
						resp.setHeader("Cache-Control", "no-transform");
						resp.setHeader("Charset", "UTF-8");
						resp.setContentType("text/event-stream");
						AsyncContext async = req.startAsync();
						ServerSentEvents sse = new ServerSentEvents(async, () -> {
							try {
								Thread.sleep(2000);
								renderer.updateServerSentEventsActive();
							} catch (InterruptedException ex) {
								Thread.currentThread().interrupt();
							}
						});
						renderer.setActive(true);
						renderer.addServerSentEvents(sse);
						return;
					}
				}
				respondBadRequest(req, resp);
			} else if (path.startsWith("/thumbnail/")) {
				String[] thumbData = path.split("/");
				if (thumbData.length == 4) {
					WebGuiRenderer renderer = getRenderer(req, thumbData[2]);
					if (renderer != null && renderer.havePermission(Permissions.WEB_PLAYER_BROWSE)) {
						StoreResource resource = renderer.getMediaStore().getResource(thumbData[3]);
						AsyncContext async = req.startAsync();
						DLNAThumbnailInputStream thumb = getMediaThumbImage(resource);
						if (thumb != null) {
							resp.setContentType(ImageFormat.PNG.equals(thumb.getFormat()) ? HTTPResource.PNG_TYPEMIME : HTTPResource.JPEG_TYPEMIME);
							resp.setHeader("Accept-Ranges", "bytes");
							resp.setHeader("Connection", "keep-alive");
							resp.setStatus(200);
							resp.setContentLengthLong(thumb.getSize());
							OutputStream os = resp.getOutputStream();
							copyStreamAsync(thumb, os, async);
							return;
						}
					}
				}
				respondBadRequest(req, resp);
			} else if (path.startsWith("/image/")) {
				String[] imageData = path.split("/");
				if (imageData.length == 4) {
					WebGuiRenderer renderer = getRenderer(req, imageData[2]);
					if (renderer != null && renderer.havePermission(Permissions.WEB_PLAYER_BROWSE) && sendImageMedia(req, resp, renderer, imageData[3])) {
						return;
					}
				}
				respondBadRequest(req, resp);
			} else if (path.startsWith("/raw/")) {
				String[] rawData = path.split("/");
				if (rawData.length == 4) {
					WebGuiRenderer renderer = getRenderer(req, rawData[2]);
					if (renderer != null && renderer.havePermission(Permissions.WEB_PLAYER_BROWSE) && sendRawMedia(req, resp, renderer, rawData[3], false)) {
						return;
					}
				}
				respondBadRequest(req, resp);
			} else if (path.startsWith("/download/")) {
				String[] rawData = path.split("/");
				if (rawData.length == 4) {
					WebGuiRenderer renderer = getRenderer(req, rawData[2]);
					if (renderer != null && renderer.havePermission(Permissions.WEB_PLAYER_DOWNLOAD) && sendDownloadMedia(req, resp, renderer, rawData[3])) {
						return;
					}
				}
				respondBadRequest(req, resp);
			} else if (path.startsWith("/media/")) {
				if (!sendMedia(req, resp, path)) {
					respondBadRequest(req, resp);
				}
			} else {
				LOGGER.trace("PlayerApiServlet request not available : {}", path);
				respondNotFound(req, resp);
			}
		} catch (RuntimeException e) {
			LOGGER.error("RuntimeException in PlayerApiServlet: {}", e.getMessage());
			LOGGER.trace("{}", e);
			respondInternalServerError(req, resp);
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		try {
			JsonObject action = getJsonObjectFromBody(req);
			if (!action.has("uuid")) {
				respondBadRequest(req, resp);
				return;
			}
			String uuid = action.get("uuid").getAsString();
			WebGuiRenderer renderer = getRenderer(req, uuid);
			if (renderer == null) {
				respondUnauthorized(req, resp);
				return;
			}
			if (!renderer.havePermission(Permissions.WEB_PLAYER_BROWSE)) {
				respondForbidden(req, resp);
				return;
			}
			renderer.setActive(true);
			var path = req.getServletPath();
			switch (path) {
				case "/browse" -> {
					if (action.has("id")) {
						String id = action.get("id").getAsString();
						String search = action.has("search") ? action.get("search").getAsString() : null;
						String lang = action.has("lang") ? action.get("lang").getAsString() : null;
						JsonObject browse = getBrowsePage(renderer, id, search, lang);
						if (browse != null) {
							respond(req, resp, browse.toString(), 200, "application/json");
							return;
						}
					}
					respondBadRequest(req, resp);
				}
				case "/logout" -> {
					ConnectedRenderers.removeWebPlayerRenderer(uuid);
					respond(req, resp, "{}", 200, "application/json");
				}
				case "/play" -> {
					if (action.has("id")) {
						String id = action.get("id").getAsString();
						String lang = action.has("lang") ? action.get("lang").getAsString() : null;
						JsonObject play = getPlayPage(renderer, id, lang);
						if (play != null) {
							respond(req, resp, play.toString(), 200, "application/json");
							return;
						}
					}
					respondBadRequest(req, resp);
				}
				case "/edit" -> {
					if (!renderer.havePermission(Permissions.WEB_PLAYER_EDIT)) {
						respondForbidden(req, resp);
					} else if (!TMDB.isReady()) {
						respondNotFound(req, resp);
					} else if (action.has("id")) {
						String id = action.get("id").getAsString();
						JsonObject edit = getEditData(renderer, id);
						if (edit != null) {
							respond(req, resp, edit.toString(), 200, "application/json");
							return;
						}
					}
					respondBadRequest(req, resp);
				}
				case "/findMetadata" -> {
					if (!renderer.havePermission(Permissions.WEB_PLAYER_EDIT)) {
						respondForbidden(req, resp);
					} else if (!TMDB.isReady()) {
						respondNotFound(req, resp);
					} else if (action.has("id") && !action.get("id").isJsonNull() &&
						action.has("media_type") && !action.get("media_type").isJsonNull() &&
						action.has("search") && !action.get("search").isJsonNull()) {
						String id = action.get("id").getAsString();
						String mediaType = action.get("media_type").getAsString();
						String search = action.get("search").getAsString();
						Integer year = action.has("year") && !action.get("year").isJsonNull() ? action.get("year").getAsInt() : null;
						String lang = action.has("lang") && !action.get("lang").isJsonNull() ? action.get("lang").getAsString() : null;
						JsonArray editResults = getMetadataResults(renderer, id, mediaType, search, year, lang);
						if (editResults != null) {
							respond(req, resp, editResults.toString(), 200, "application/json");
							return;
						}
					}
					respondBadRequest(req, resp);
				}
				case "/setMetadata" -> {
					if (!renderer.havePermission(Permissions.WEB_PLAYER_EDIT)) {
						respondForbidden(req, resp);
					} else if (!TMDB.isReady()) {
						respondNotFound(req, resp);
					} else if (action.has("id") && !action.get("id").isJsonNull() &&
						action.has("tmdb_id") && !action.get("tmdb_id").isJsonNull() &&
						action.has("media_type") && !action.get("media_type").isJsonNull()) {
						String id = action.get("id").getAsString();
						Long tmdbId = action.get("tmdb_id").getAsLong();
						String mediaType = action.get("media_type").getAsString();
						//Long episode = action.has("episode") && !action.get("episode").isJsonNull() ? action.get("episode").getAsLong() : null;
						//Long season = action.has("season") && !action.get("season").isJsonNull() ? action.get("season").getAsLong() : null;
						boolean changed = setMetadata(renderer, id, tmdbId, mediaType);
						if (changed) {
							respond(req, resp, "{}", 200, "application/json");
							return;
						}
					}
					respondBadRequest(req, resp);
				}
				case "/show" -> {
					if (action.has("id")) {
						String id = action.get("id").getAsString();
						String lang = action.has("lang") ? action.get("lang").getAsString() : null;
						JsonObject show = getShowPage(renderer, id, lang);
						if (show != null) {
							respond(req, resp, show.toString(), 200, "application/json");
							return;
						}
					}
					respondBadRequest(req, resp);
				}
				case "/status" -> {
					if (action.has("uuid")) {
						((WebGuiPlayer) renderer.getPlayer()).setDataFromJson(action.toString());
						respond(req, resp, "", 200, "application/json");
					} else {
						respondBadRequest(req, resp);
					}
				}
				default -> {
					LOGGER.trace("PlayerApiServlet request not available : {}", path);
					respondNotFound(req, resp);
				}

			}
		} catch (RuntimeException e) {
			LOGGER.error("Exception in PlayerApiServlet: {}", e.getMessage());
			LOGGER.trace("{}", e);
			respondInternalServerError(req, resp);
		} catch (InterruptedException e) {
			respondInternalServerError(req, resp);
			Thread.currentThread().interrupt();
		}
	}

	private static synchronized WebGuiRenderer getRenderer(HttpServletRequest req, String uuid) {
		ConnectedRenderers.RENDERER_LOCK.lock();
		try {
			if (ConnectedRenderers.hasWebPlayerRenderer(uuid)) {
				return ConnectedRenderers.getWebPlayerRenderer(uuid);
			}
			if (ConnectedRenderers.isValidUUID(uuid)) {
				createRenderer(req, uuid);
				if (ConnectedRenderers.hasWebPlayerRenderer(uuid)) {
					return ConnectedRenderers.getWebPlayerRenderer(uuid);
				}
			}
		} finally {
			ConnectedRenderers.RENDERER_LOCK.unlock();
		}
		return null;
	}

	private static void createRenderer(HttpServletRequest req, String uuid) {
		Account account = AuthService.getPlayerAccountLoggedIn(req);
		if (account == null || !account.havePermission(Permissions.WEB_PLAYER_BROWSE)) {
			return;
		}
		try {
			LOGGER.info("Founded new web gui renderer with uuid: {}", uuid);
			String userAgent = req.getHeader("User-agent");
			String langs = getRequestLanguages(req);
			WebGuiRenderer renderer = new WebGuiRenderer(uuid, account.getUser().getId(), userAgent, langs);
			renderer.associateIP(getInetAddress(req));
			renderer.setActive(true);
			ConnectedRenderers.addWebPlayerRenderer(renderer);
			LOGGER.debug("Created web gui renderer for " + renderer.getRendererName());
		} catch (ConfigurationException ex) {
			LOGGER.warn("Error in loading configuration of WebPlayerRenderer");
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private JsonObject getBrowsePage(WebGuiRenderer renderer, String id, String search, String lang) throws IOException, InterruptedException {
		PMS.REALTIME_LOCK.lockInterruptibly();
		try {
			LOGGER.debug("Make browse page " + id);
			JsonObject result = new JsonObject();
			result.addProperty("goal", "browse");
			JsonArray jBreadcrumbs = new JsonArray();
			JsonArray jFolders = new JsonArray();
			JsonArray mediaLibraryFolders = new JsonArray();
			JsonArray jMedias = new JsonArray();
			StoreResource rootResource = id.equals("0") ? null : renderer.getMediaStore().getResource(id);

			List<StoreResource> resources = renderer.getMediaStore().getResources(id, true, 0, 0, search, lang);
			if (!resources.isEmpty() &&
					resources.get(0).getParent() != null &&
					(resources.get(0).getParent() instanceof CodeEnter)) {
				return null;
			}
			if (StringUtils.isNotEmpty(search) && !(resources instanceof CodeEnter)) {
				UMSUtils.filterResourcesByName(resources, search, false, false);
			}

			boolean hasFile = false;
			if (!resources.isEmpty() &&
					resources.get(0).getParent() != null &&
					resources.get(0).getParent().isFolder()) {
				StoreResource thisResourceFromResources = resources.get(0).getParent();
				String thisName = thisResourceFromResources.getSystemName();
				if (thisName.equals("MediaLibrary")) {
					for (StoreResource resource : resources) {
						String icon = switch (resource.getSystemName()) {
							case "Video" ->
								"video";
							case "Audio" ->
								"audio";
							case "Photo" ->
								"image";
							default ->
								"folder";
						};
						hasFile = true;
						JsonObject jMedia = new JsonObject();
						jMedia.addProperty("id", resource.getResourceId());
						jMedia.addProperty("name", resource.getLocalizedDisplayName(lang));
						jMedia.addProperty("icon", icon);
						jMedias.add(jMedia);
					}
				}
				jBreadcrumbs = getBreadcrumbs(thisResourceFromResources, lang);

				if (resources.get(0).getParent().getParent() != null) {
					StoreResource parentFromResources = resources.get(0).getParent().getParent();
					JsonObject jFolder = new JsonObject();
					jFolder.addProperty("id", parentFromResources.getResourceId());
					jFolder.addProperty("name", "..");
					jFolder.addProperty("icon", "back");
					jFolders.add(jFolder);
				}
			}
			if (resources.isEmpty() && rootResource != null && rootResource.isFolder()) {
				jBreadcrumbs = getBreadcrumbs(rootResource, lang);
				if (rootResource.getParent() != null) {
					StoreResource parentFromResources = rootResource.getParent();
					JsonObject jFolder = new JsonObject();
					jFolder.addProperty("id", parentFromResources.getResourceId());
					jFolder.addProperty("name", "..");
					jFolder.addProperty("icon", "back");
					jFolders.add(jFolder);
				}
			}

			// Generate innerHtml snippets for folders and media items
			for (StoreResource resource : resources) {
				if (resource == null) {
					continue;
				}
				if (resource instanceof VirtualVideoAction) {
					// Let's take the VVA real early
					hasFile = true;
					JsonObject jMedia = new JsonObject();
					jMedia.addProperty("id", resource.getResourceId());
					jMedia.addProperty("name", resource.getLocalizedDisplayName(lang));
					jMedias.add(jMedia);
					continue;
				}

				if (resource.isFolder()) {
					Boolean isDisplayFoldersAsThumbnails = false;
					/*
					* Display folders as thumbnails instead of down the left side if:
					* - The parent is TV Shows, or
					* - This is a filtered metadata folder within TV shows, or
					* - This is Recommendations
					 */
					if (resource.getParent().getSystemName().equals("TvShows") ||
							resource.getParent().getSystemName().equals("Recommendations") ||
							(resource.getParent().getParent() != null &&
							resource.getParent().getParent().getSystemName().equals("FilterByProgress")) ||
							(resource.getParent().getParent() != null &&
							resource.getParent().getParent().getParent() != null &&
							resource.getParent().getParent().getParent().getSystemName().equals("FilterByInformation"))) {
						isDisplayFoldersAsThumbnails = true;
					}

					if (!isDisplayFoldersAsThumbnails || !(isDisplayFoldersAsThumbnails && resource instanceof MediaLibraryFolder)) {
						boolean addFolderToFoldersListOnLeft = true;

						// Populate the front page
						if (id.equals("0") && resource.getSystemName().equals("MediaLibrary")) {
							List<StoreResource> videoSearchResults = renderer.getMediaStore().getResources(resource.getId(), true, 0, 0, Messages.getString("Video"));
							UMSUtils.filterResourcesByName(videoSearchResults, Messages.getString("Video"), true, true);
							JsonObject mediaLibraryFolder = new JsonObject();
							StoreResource videoFolder = null;
							if (!videoSearchResults.isEmpty()) {
								videoFolder = videoSearchResults.get(0);
								mediaLibraryFolder.addProperty("id", videoFolder.getResourceId());
								mediaLibraryFolder.addProperty("name", videoFolder.getLocalizedDisplayName(lang));
								mediaLibraryFolder.addProperty("icon", "video");
								mediaLibraryFolders.add(mediaLibraryFolder);
							}

							List<StoreResource> audioSearchResults = renderer.getMediaStore().getResources(resource.getId(), true, 0, 0, Messages.getString("Audio"));
							UMSUtils.filterResourcesByName(audioSearchResults, Messages.getString("Audio"), true, true);
							if (!audioSearchResults.isEmpty()) {
								StoreResource audioFolder = audioSearchResults.get(0);
								mediaLibraryFolder = new JsonObject();
								mediaLibraryFolder.addProperty("id", audioFolder.getResourceId());
								mediaLibraryFolder.addProperty("name", audioFolder.getLocalizedDisplayName(lang));
								mediaLibraryFolder.addProperty("icon", "audio");
								mediaLibraryFolders.add(mediaLibraryFolder);
							}

							List<StoreResource> imageSearchResults = renderer.getMediaStore().getResources(resource.getId(), true, 0, 0, Messages.getString("Photo"));
							UMSUtils.filterResourcesByName(imageSearchResults, Messages.getString("Photo"), true, true);
							if (!imageSearchResults.isEmpty()) {
								StoreResource imagesFolder = imageSearchResults.get(0);
								mediaLibraryFolder = new JsonObject();
								mediaLibraryFolder.addProperty("id", imagesFolder.getResourceId());
								mediaLibraryFolder.addProperty("name", imagesFolder.getLocalizedDisplayName(lang));
								mediaLibraryFolder.addProperty("icon", "image");
								mediaLibraryFolders.add(mediaLibraryFolder);
							}

							if (videoFolder != null) {
								JsonObject jMediasSelections = new JsonObject();
								jMediasSelections.add("recentlyAdded", getMediaLibraryFolderChilds(videoFolder, renderer, Messages.getString("RecentlyAdded"), lang));
								jMediasSelections.add("recentlyPlayed", getMediaLibraryFolderChilds(videoFolder, renderer, Messages.getString("RecentlyPlayed"), lang));
								jMediasSelections.add("inProgress", getMediaLibraryFolderChilds(videoFolder, renderer, Messages.getString("InProgress"), lang));
								jMediasSelections.add("mostPlayed", getMediaLibraryFolderChilds(videoFolder, renderer, Messages.getString("MostPlayed"), lang));
								result.add("mediasSelections", jMediasSelections);
								addFolderToFoldersListOnLeft = false;
							}
						}

						if (addFolderToFoldersListOnLeft) {
							// The HlsHelper is a folder
							JsonObject jFolder = new JsonObject();
							jFolder.addProperty("id", resource.getResourceId());
							jFolder.addProperty("name", resource.getLocalizedDisplayName(lang));
							jFolders.add(jFolder);
						}
					}
				} else {
					// The HlsHelper is a media file
					hasFile = true;
					jMedias.add(getMediaJsonObject(resource, lang));
				}
			}

			if (rootResource instanceof MediaLibraryFolder folder) {
				if (folder.isTVSeries()) {
					JsonObject metadata = getMetadataAsJsonObject(rootResource, renderer, lang);
					if (metadata != null) {
						metadata.addProperty("isEditable", renderer.havePermission(Permissions.WEB_PLAYER_EDIT) && TMDB.isReady());
						result.add("metadata", metadata);
					}
				}

				// Check whether this HlsHelper is expected to contain folders that display as big thumbnails
				if (folder.getSystemName().equals("TvShows") ||
						folder.getSystemName().equals("Recommendations") ||
						(folder.getParent() != null &&
						folder.getParent().getSystemName().equals("FilterByProgress")) ||
						(folder.getParent() != null &&
						folder.getParent().getParent() != null &&
						folder.getParent().getParent().getSystemName().equals("FilterByInformation"))) {
					for (StoreResource resource : resources) {
						if (resource instanceof MediaLibraryFolder) {
							hasFile = true;
							jMedias.add(getMediaJsonObject(resource, lang));
						}
					}
				}
			}

			result.addProperty("umsversion", PropertiesUtil.getProjectProperties().get("project.version"));
			result.addProperty("name", id.equals("0") || rootResource == null ? CONFIGURATION.getServerDisplayName() : rootResource.getLocalizedDisplayName(lang));
			result.addProperty("hasFile", hasFile);
			result.addProperty("useWebControl", CONFIGURATION.useWebPlayerControls());
			result.add("breadcrumbs", jBreadcrumbs);
			result.add("mediaLibraryFolders", mediaLibraryFolders);
			result.add("folders", jFolders);
			result.add("medias", jMedias);
			return result;
		} finally {
			PMS.REALTIME_LOCK.unlock();
		}
	}

	private JsonObject getMediaJsonObject(StoreResource resource, String lang) {
		JsonObject jMedia = new JsonObject();
		if (resource.isFolder()) {
			jMedia.addProperty("goal", "browse");
			jMedia.addProperty("name", resource.getLocalizedDisplayName(lang));
		} else if (resource instanceof StoreItem item) {
			if (item.getFormat() != null && item.getFormat().isVideo()) {
				jMedia.addProperty("goal", "show");
			} else {
				jMedia.addProperty("goal", "play");
			}
			jMedia.addProperty("name", item.getLocalizedResumeName(lang));
		}
		jMedia.addProperty("id", resource.getResourceId());
		return jMedia;
	}

	private JsonArray getBreadcrumbs(StoreResource resource, String lang) {
		JsonArray jBreadcrumbs = new JsonArray();
		JsonObject jBreadcrumb = new JsonObject();
		jBreadcrumb.addProperty("id", "");
		jBreadcrumb.addProperty("name", resource.getLocalizedDisplayName(lang));
		jBreadcrumbs.add(jBreadcrumb);
		StoreResource thisResourceFromResources = resource;
		while (thisResourceFromResources.getParent() != null && thisResourceFromResources.getParent().isFolder()) {
			thisResourceFromResources = thisResourceFromResources.getParent();
			jBreadcrumb = new JsonObject();
			jBreadcrumb.addProperty("id", thisResourceFromResources.getResourceId());
			jBreadcrumb.addProperty("name", thisResourceFromResources.getLocalizedDisplayName(lang));
			jBreadcrumbs.add(jBreadcrumb);
		}
		JsonArray jBreadcrumbsInverted = new JsonArray();
		for (int i = jBreadcrumbs.size(); i > 0; i--) {
			jBreadcrumbsInverted.add(jBreadcrumbs.get(i - 1));
		}
		return jBreadcrumbsInverted;
	}

	private JsonArray getMediaLibraryFolderChilds(
			StoreResource videoFolder,
			Renderer renderer,
			String folderName,
			String lang
	) throws IOException {
		List<StoreResource> videoFolderChildren = renderer.getMediaStore().getResources(videoFolder.getId(), true, 0, 0, folderName);
		UMSUtils.filterResourcesByName(videoFolderChildren, folderName, true, true);
		if (videoFolderChildren.isEmpty()) {
			LOGGER.trace("The videoFolderChildren folder was empty after filtering for " + folderName);
			return null;
		}
		JsonArray jLibraryVideos = new JsonArray();
		StoreResource librayFolder = videoFolderChildren.get(0);
		List<StoreResource> libraryVideos = renderer.getMediaStore().getResources(librayFolder.getId(), true, 0, 6);

		for (StoreResource libraryVideo : libraryVideos) {
			// Skip the #--TRANSCODE--# and \#--LIVE SUBTITLES--\# entries
			if (libraryVideo.getSystemName().equals("LiveSubtitles_FolderName") || libraryVideo instanceof TranscodeVirtualFolder) {
				continue;
			}
			jLibraryVideos.add(getMediaJsonObject(libraryVideo, lang));
		}
		return jLibraryVideos;
	}

	private JsonObject getShowPage(WebGuiRenderer renderer, String id, String lang) throws IOException, InterruptedException {
		JsonObject result = getPlayPage(renderer, id, lang);
		if (result != null) {
			result.remove("goal");
			result.addProperty("goal", "show");
		}
		return result;
	}

	private JsonObject getPlayPage(WebGuiRenderer renderer, String id, String lang) throws IOException, InterruptedException {
		PMS.REALTIME_LOCK.lockInterruptibly();
		try {
			LOGGER.debug("Make play page " + id);
			JsonObject result = new JsonObject();
			result.addProperty("goal", "play");
			JsonArray jFolders = new JsonArray();
			JsonArray medias = new JsonArray();
			JsonObject media = new JsonObject();

			StoreResource resource = renderer.getMediaStore().getResource(id);
			StoreItem item = resource instanceof StoreItem libraryItem ? libraryItem : null;
			if (item == null) {
				LOGGER.debug("Bad web play id: " + id);
				throw new IOException("Bad Id");
			}

			if (item.getParent() != null &&
					item.getParent().isFolder()) {
				JsonObject jFolder = new JsonObject();
				jFolder.addProperty("id", item.getParent().getResourceId());
				jFolder.addProperty("name", "..");
				jFolders.add(jFolder);
			}

			Format format = item.getFormat();
			boolean isImage = format.isImage();
			boolean isVideo = format.isVideo();
			boolean isAudio = format.isAudio();

			String mime = renderer.getMimeType(item);
			media.addProperty("mediaType", isVideo ? "video" : isAudio ? "audio" : isImage ? "image" : "");
			if (isVideo) {
				JsonObject metadata = getMetadataAsJsonObject(item, renderer, lang);
				if (metadata != null) {
					metadata.addProperty("isEditable", renderer.havePermission(Permissions.WEB_PLAYER_EDIT) && TMDB.isReady());
					media.add("metadata", metadata);
				}
				media.addProperty("isVideoWithChapters", item.getMediaInfo() != null && item.getMediaInfo().hasChapters());
				mime = renderer.getVideoMimeType();
				if (item.getMediaStatus() != null && item.getMediaStatus().getLastPlaybackPosition() != null && item.getMediaStatus().getLastPlaybackPosition() > 0) {
					media.addProperty("resumePosition", item.getMediaStatus().getLastPlaybackPosition().intValue());
				}
			}

			// Controls whether to use the browser's native audio player
			// Audio types that are natively supported by all major browsers:
			if (isAudio) {
				media.addProperty("isNativeAudio", mime.equals(HTTPResource.AUDIO_MP3_TYPEMIME));
			}

			media.addProperty("name", item.getLocalizedResumeName(lang));
			media.addProperty("id", id);
			media.addProperty("autoContinue", CONFIGURATION.getWebPlayerAutoCont(format));
			media.addProperty("isDynamicPls", CONFIGURATION.isDynamicPls());
			media.addProperty("isDownload", renderer.havePermission(Permissions.WEB_PLAYER_DOWNLOAD) && CONFIGURATION.useWebPlayerDownload());

			media.add("surroundMedias", getSurroundingByType(item, lang));

			if (isImage) {
				// do this like this to simplify the code
				// skip all player crap since img tag works well
				int delay = CONFIGURATION.getWebPlayerImgSlideDelay() * 1000;
				if (delay > 0 && CONFIGURATION.getWebPlayerAutoCont(format)) {
					media.addProperty("delay", delay);
				}
			} else {
				media.addProperty("mime", mime);
			}

			medias.add(media);
			result.add("medias", medias);
			result.add("folders", jFolders);
			result.add("breadcrumbs", getBreadcrumbs(item, lang));
			result.addProperty("useWebControl", CONFIGURATION.useWebPlayerControls());
			return result;
		} finally {
			PMS.REALTIME_LOCK.unlock();
		}
	}

	private JsonObject getEditData(WebGuiRenderer renderer, String id) throws IOException, InterruptedException {
		PMS.REALTIME_LOCK.lockInterruptibly();
		try {
			LOGGER.debug("Make edit data " + id);
			StoreResource resource = renderer.getMediaStore().getResource(id);
			StoreItem item = resource instanceof StoreItem libraryItem ? libraryItem : null;
			MediaLibraryTvSeries tvSeries = resource instanceof MediaLibraryTvSeries mediaLibraryTvSeries ? mediaLibraryTvSeries : null;
			if (tvSeries == null && item == null) {
				LOGGER.debug("Bad web edit id: " + id);
				throw new IOException("Bad Id");
			}
			JsonObject result = new JsonObject();
			if (item != null && item.getMediaInfo().isVideo() &&
					item.getMediaInfo().hasVideoMetadata()) {
				MediaVideoMetadata metadata = item.getMediaInfo().getVideoMetadata();
				String search = metadata.getMovieOrShowName();
				if (item instanceof RealFile realFile && realFile.getFile() != null) {
					String filename = realFile.getFile().getName();
					String absolutePath = realFile.getFile().getParent();
					result.addProperty("filename", filename);
					result.addProperty("folder", absolutePath);
					if (StringUtils.isBlank(search)) {
						search = FileUtil.basicPrettify(filename);
					}
				}
				result.addProperty("search", search);
				if (metadata.isTvEpisode()) {
					result.addProperty("media_type", "tv_episode");
					result.addProperty("episode", getLong(metadata.getTvEpisodeNumberUnpadded()));
					result.addProperty("season", metadata.getTvSeason());
					if (metadata.getSeriesMetadata() != null) {
						result.addProperty("year", metadata.getSeriesMetadata().getStartYear());
					}
				} else {
					result.addProperty("media_type", "movie");
					result.addProperty("year", metadata.getYear());
				}
			} else if (tvSeries != null && tvSeries.getTvSeriesMetadata() != null) {
				TvSeriesMetadata metadata = tvSeries.getTvSeriesMetadata();
				result.addProperty("search", metadata.getTitle());
				result.addProperty("year", metadata.getStartYear());
				result.addProperty("media_type", "tv");
			}
			return result;
		} finally {
			PMS.REALTIME_LOCK.unlock();
		}
	}

	private JsonArray getMetadataResults(WebGuiRenderer renderer, String id, String mediaType, String search, Integer year, String lang) throws IOException, InterruptedException {
		PMS.REALTIME_LOCK.lockInterruptibly();
		try {
			LOGGER.debug("Make metadata results " + id);
			StoreResource resource = renderer.getMediaStore().getResource(id);
			StoreItem item = resource instanceof StoreItem libraryItem ? libraryItem : null;
			MediaLibraryTvSeries tvSeries = resource instanceof MediaLibraryTvSeries mediaLibraryTvSeries ? mediaLibraryTvSeries : null;
			if (item == null && tvSeries == null) {
				LOGGER.debug("Bad web edit id: " + id);
				throw new IOException("Bad Id");
			}
			JsonArray result = null;
			if ("tv".equals(mediaType) || "tv_episode".equals(mediaType)) {
				Long currentId;
				if (item != null &&
					item.getMediaInfo() != null &&
					item.getMediaInfo().hasVideoMetadata() &&
					item.getMediaInfo().getVideoMetadata().isTvEpisode()) {
					currentId = item.getMediaInfo().getVideoMetadata().getTmdbTvId();
				} else if (tvSeries != null &&
					tvSeries.getTvSeriesMetadata() != null) {
					currentId = tvSeries.getTvSeriesMetadata().getTmdbId();
				} else {
					currentId = null;
				}
				result = TMDB.getTvShowsFromEpisode(search, year, lang, currentId);
			} else if ("movie".equals(mediaType) && item != null) {
				Long currentId;
				if (item.getMediaInfo() != null &&
					item.getMediaInfo().hasVideoMetadata() &&
					!item.getMediaInfo().getVideoMetadata().isTvEpisode()) {
					currentId = item.getMediaInfo().getVideoMetadata().getTmdbId();
				} else {
					currentId = null;
				}
				result = TMDB.getMovies(search, year, lang, currentId);
			}
			return result;
		} finally {
			PMS.REALTIME_LOCK.unlock();
		}
	}

	private boolean setMetadata(WebGuiRenderer renderer, String id, Long tmdbId, String mediaType) throws IOException, InterruptedException {
		PMS.REALTIME_LOCK.lockInterruptibly();
		try {
			LOGGER.debug("Setd metadata " + id);
			StoreResource resource = renderer.getMediaStore().getResource(id);
			StoreItem item = resource instanceof StoreItem libraryItem ? libraryItem : null;
			MediaLibraryTvSeries tvSeries = resource instanceof MediaLibraryTvSeries mediaLibraryTvSeries ? mediaLibraryTvSeries : null;
			if ((tvSeries == null && item == null) || tmdbId == null) {
				LOGGER.debug("Bad metadata id: " + id);
				throw new IOException("Bad Id");
			}
			if (item != null && item.getMediaInfo() != null && item.getMediaInfo().getFileId() != null) {
				if (mediaType.equals("tv_episode")) {
					return TMDB.updateTvShowForEpisode(item.getMediaInfo(), tmdbId);
				} else if (mediaType.equals("movie")) {
					return TMDB.updateMovieMetadata(item.getMediaInfo(), tmdbId);
				}
			} else if (mediaType.equals("tv") && tvSeries != null && tvSeries.getTvSeriesMetadata() != null && tvSeries.getTvSeriesMetadata().getTvSeriesId() != null) {
				return TMDB.updateTvShowMetadata(tvSeries.getTvSeriesMetadata().getTvSeriesId(), tmdbId);
			}
			return false;
		} finally {
			PMS.REALTIME_LOCK.unlock();
		}
	}

	private static JsonObject getSurroundingByType(StoreItem item, String lang) {
		JsonObject result = new JsonObject();
		List<StoreResource> children = item.getParent().getChildren();
		boolean looping = CONFIGURATION.getWebPlayerAutoLoop(item.getFormat());
		int type = item.getType();
		int size = children.size();
		int mod = looping ? size : 9999;
		int self = children.indexOf(item);
		for (int step = -1; step < 2; step += 2) {
			int i = self;
			int offset = (step < 0 && looping) ? size : 0;
			StoreResource next = null;
			while (true) {
				i = (offset + i + step) % mod;
				if (i >= size || i < 0 || i == self) {
					break; // Not found
				}
				next = children.get(i);
				if (next instanceof StoreItem storeItem && storeItem.getType() == type) {
					break; // Found
				}
				next = null;
			}
			if (next != null && next instanceof StoreItem storeItem) {
				JsonObject jMedia = new JsonObject();
				jMedia.addProperty("id", storeItem.getResourceId());
				jMedia.addProperty("name", storeItem.getLocalizedResumeName(lang));
				result.add(step > 0 ? "next" : "prev", jMedia);
			}
		}
		return result;
	}

	private DLNAThumbnailInputStream getMediaThumbImage(StoreResource resource) {
		if (resource == null) {
			return null;
		}
		DLNAThumbnailInputStream in;
		resource.checkThumbnail();
		try {
			in = resource.fetchThumbnailInputStream();
			if (in == null) {
				// if r is null for some reason, default to generic thumb
				in = resource.getGenericThumbnailInputStream(null);
			}
		} catch (IOException ex) {
			return null;
		}
		BufferedImageFilterChain filterChain = null;
		if (resource.isFullyPlayedMark()) {
			filterChain = new BufferedImageFilterChain(FullyPlayed.getOverlayFilter());
		}
		filterChain = resource.addFlagFilters(filterChain);
		if (filterChain != null) {
			try {
				in = in.transcode(in.getDLNAImageProfile(), false, filterChain);
			} catch (IOException ex) {
			}
		}
		return in;
	}

	private boolean sendRawMedia(HttpServletRequest req, HttpServletResponse resp, WebGuiRenderer renderer, String id, boolean isDownload) {
		List<StoreResource> res;
		try {
			res = renderer.getMediaStore().getResources(id, false, 0, 0);
			StoreItem item;
			if (res.size() == 1) {
				StoreResource resource = res.get(0);
				item = resource instanceof StoreItem storeItem ? storeItem : null;
			} else {
				// another error
				item = null;
			}
			if (item == null) {
				LOGGER.debug("media unkonwn");
				return false;
			}
			long len = item.length();
			item.setEngine(null);
			ByteRange range = parseRange(req, len);
			AsyncContext async = req.startAsync();
			InputStream in = item.getInputStream(range);
			if (len == 0) {
				// For web resources actual length may be unknown until we open the stream
				len = item.length();
			}
			String mime = renderer.getMimeType(item);
			resp.setContentType(mime);
			resp.setHeader("Accept-Ranges", "bytes");
			resp.setHeader("Server", PMS.get().getServerName());
			resp.setHeader("Connection", "keep-alive");

			if (isDownload) {
				resp.setHeader("Content-Disposition", "attachment; filename=\"" + new File(item.getFileName()).getName() + "\"");
			}
			if (in != null) {
				if (in.available() != len) {
					resp.setHeader("Content-Range", "bytes " + range.getStart() + "-" + in.available() + "/" + len);
					resp.setStatus(206);
					resp.setContentLength(in.available());
				} else {
					resp.setStatus(200);
					resp.setContentLength(in.available());
				}
				if (LOGGER.isTraceEnabled()) {
					logHttpServletResponse(req, resp, null, true);
				}
				OutputStream os = new BufferedOutputStream(resp.getOutputStream(), 512 * 1024);
				LOGGER.debug("start raw dump");
				copyStreamAsync(in, os, async);
			} else {
				resp.setStatus(500);
				resp.setContentLength(0);
				async.complete();
			}
		} catch (IOException ex) {
			return false;
		}
		return true;
	}

	private boolean sendDownloadMedia(HttpServletRequest req, HttpServletResponse resp, WebGuiRenderer renderer, String id) {
		List<StoreResource> res;
		try {
			res = renderer.getMediaStore().getResources(id, false, 0, 0);
			StoreItem item;
			if (res.size() == 1) {
				StoreResource resource = res.get(0);
				item = resource instanceof StoreItem storeItem ? storeItem : null;
			} else {
				// another error
				item = null;
			}
			if (item == null) {
				LOGGER.debug("media unkonwn");
				return false;
			}
			File media = new File(item.getFileName());
			String mime = renderer.getMimeType(item);
			resp.setContentType(mime);
			resp.setHeader("Server", PMS.get().getServerName());
			resp.setHeader("Connection", "keep-alive");
			resp.setHeader("Content-Disposition", "attachment; filename=\"" + media.getName() + "\"");
			resp.setStatus(200);
			resp.setContentLengthLong(media.length());
			InputStream in = item.getInputStream();
			if (LOGGER.isTraceEnabled()) {
				logHttpServletResponse(req, resp, null, true);
			}
			AsyncContext async = req.startAsync();
			OutputStream os = resp.getOutputStream();
			copyStreamAsync(in, os, async);
		} catch (IOException ex) {
			return false;
		}
		return true;
	}

	private boolean sendImageMedia(HttpServletRequest req, HttpServletResponse resp, WebGuiRenderer renderer, String id) {
		List<StoreResource> res;
		try {
			res = renderer.getMediaStore().getResources(id, false, 0, 0);
			StoreItem item;
			if (res.size() == 1) {
				StoreResource resource = res.get(0);
				item = resource instanceof StoreItem storeItem ? storeItem : null;
			} else {
				// another error
				item = null;
			}
			if (item == null) {
				LOGGER.debug("media unkonwn");
				return false;
			}
			String mime;
			InputStream in;
			long len;
			ByteRange range;
			if (item.getMediaInfo() != null && item.getMediaInfo().isImage() && item.getMediaInfo().getImageInfo() != null) {
				ImageInfo imageInfo = item.getMediaInfo().getImageInfo();
				boolean supported = renderer.isImageFormatSupported(imageInfo.getFormat());
				mime = item.getFormat() != null ?
						item.getFormat().mimeType() :
						renderer.getMimeType(item);

				len = supported && imageInfo.getSize() != ImageInfo.SIZE_UNKNOWN ? imageInfo.getSize() : item.length();

				if (supported) {
					in = item.getInputStream();
				} else {
					InputStream imageInputStream;
					if (item.getEngine() instanceof ImageEngine) {
						ProcessWrapper transcodeProcess = item.getEngine().launchTranscode(item,
								item.getMediaInfo(),
								new OutputParams(PMS.getConfiguration())
						);
						imageInputStream = transcodeProcess != null ? transcodeProcess.getInputStream(0) : null;
					} else {
						imageInputStream = item.getInputStream();
					}
					Image image = Image.toImage(imageInputStream, 3840, 2400, ImagesUtil.ScaleType.MAX, ImageFormat.JPEG, false);
					len = image == null ? 0 : image.getBytes(false).length;
					in = image == null ? null : new ByteArrayInputStream(image.getBytes(false));
				}
				range = new ByteRange(0L, len);
			} else {
				return false;
			}
			AsyncContext async = req.startAsync();
			resp.setContentType(mime);
			resp.setHeader("Accept-Ranges", "bytes");
			resp.setHeader("Server", PMS.get().getServerName());
			resp.setHeader("Connection", "keep-alive");
			if (in != null) {
				if (in.available() != len) {
					resp.setHeader("Content-Range", "bytes " + range.getStart() + "-" + in.available() + "/" + len);
					resp.setStatus(206);
					resp.setContentLength(in.available());
				} else {
					resp.setStatus(200);
					resp.setContentLength(in.available());
				}
				if (LOGGER.isTraceEnabled()) {
					logHttpServletResponse(req, resp, null, true);
				}
				OutputStream os = new BufferedOutputStream(resp.getOutputStream(), 512 * 1024);
				copyStreamAsync(in, os, async);
			} else {
				resp.setStatus(500);
				resp.setContentLength(0);
				async.complete();
			}
		} catch (IOException ex) {
			return false;
		}
		return true;
	}

	private static boolean sendMedia(HttpServletRequest req, HttpServletResponse resp, String path) {
		String[] rawData = path.split("/");
		if (rawData.length < 4) {
			return false;
		}
		String sessionId = rawData[2];
		String resourceId = rawData[3];
		String uri = req.getRequestURI();
		WebGuiRenderer renderer = getRenderer(req, sessionId);
		if (renderer == null || !renderer.havePermission(Permissions.WEB_PLAYER_BROWSE)) {
			return false;
		}
		StoreResource resource = renderer.getMediaStore().getResource(resourceId);
		StoreItem item = resource instanceof StoreItem storeItem ? storeItem : null;

		if (item == null) {
			// another error
			LOGGER.debug("media unkonwn");
			return false;
		}
		MediaSubtitle sid = null;
		String mimeType = renderer.getMimeType(item);
		MediaInfo media = item.getMediaInfo();
		if (media == null) {
			media = new MediaInfo();
			item.setMediaInfo(media);
		}
		if (mimeType.equals(FormatConfiguration.MIMETYPE_AUTO) && media.getMimeType() != null) {
			mimeType = media.getMimeType();
		}
		if (item.getFormat().isVideo()) {
			mimeType = renderer.getVideoMimeType();
			if (FileUtil.isUrl(item.getFileName())) {
				if (FFmpegWebVideo.isYouTubeURL(item.getFileName())) {
					item.setEngine(EngineFactory.getEngine(StandardEngineId.YOUTUBE_DL, false, false));
				} else {
					item.setEngine(EngineFactory.getEngine(StandardEngineId.FFMPEG_WEB_VIDEO, false, false));
				}
			} else if (!(item instanceof DVDISOTitle)) {
				item.setEngine(EngineFactory.getEngine(StandardEngineId.FFMPEG_VIDEO, false, false));
			}
			if (PMS.getConfiguration().getWebPlayerSubs() &&
					item.getMediaSubtitle() != null &&
					item.getMediaSubtitle().isExternal()) {
				// fetched on the side
				sid = item.getMediaSubtitle();
				item.setMediaSubtitle(null);
			}
		} else if (item.getFormat().isAudio() && !directmime(mimeType)) {
			item.setEngine(EngineFactory.getEngine(StandardEngineId.FFMPEG_AUDIO, false, false));
		}

		try {
			//hls part
			if (item.getFormat().isVideo() && HTTPResource.HLS_TYPEMIME.equals(renderer.getVideoMimeType())) {
				resp.setHeader("Server", PMS.get().getServerName());
				if (uri.endsWith("/chapters.vtt")) {
					String response = HlsHelper.getChaptersWebVtt(item);
					respond(req, resp, response, 200, HTTPResource.WEBVTT_TYPEMIME);
				} else if (uri.endsWith("/chapters.json")) {
					String response = HlsHelper.getChaptersHls(item);
					respond(req, resp, response, 200, HTTPResource.JSON_TYPEMIME);
				} else if (rawData.length > 5 && "hls".equals(rawData[4])) {
					if (rawData[5].endsWith(".m3u8")) {
						String rendition = rawData[5];
						rendition = rendition.replace(".m3u8", "");
						String response = HlsHelper.getHLSm3u8ForRendition(item, renderer, req.getContextPath() + "/media/" + sessionId + "/", rendition);
						respond(req, resp, response, 200, HTTPResource.HLS_TYPEMIME);
					} else {
						//we need to hls stream
						AsyncContext async = req.startAsync();
						InputStream in = HlsHelper.getInputStream(uri, item);

						if (in != null) {
							resp.setHeader("Connection", "keep-alive");
							if (uri.endsWith(".ts")) {
								resp.setContentType(HTTPResource.MPEGTS_BYTESTREAM_TYPEMIME);
							} else if (uri.endsWith(".vtt")) {
								resp.setContentType(HTTPResource.WEBVTT_TYPEMIME);
							}
							resp.setHeader("Transfer-Encoding", "chunked");
							resp.setStatus(200);
							renderer.start(item);
							if (LOGGER.isTraceEnabled()) {
								logHttpServletResponse(req, resp, null, true);
							}
							OutputStream os = new BufferedOutputStream(resp.getOutputStream(), 512 * 1024);
							copyStreamAsync(in, os, async);
						} else {
							resp.setStatus(500);
							resp.setContentLength(0);
							async.complete();
						}
					}
				} else {
					String response = HlsHelper.getHLSm3u8(item, renderer, req.getContextPath() + "/media/" + sessionId + "/");
					respond(req, resp, response, 200, HTTPResource.HLS_TYPEMIME);
				}
			} else {
				AsyncContext async = req.startAsync();
				ByteRange range = parseRange(req, item.length());
				LOGGER.debug("Sending {} with mime type {} to {}", item, mimeType, renderer);
				InputStream in = item.getInputStream(range);
				long len = item.length();
				boolean isTranscoding = len == StoreResource.TRANS_SIZE;
				resp.setContentType(mimeType);
				resp.setHeader("Server", PMS.get().getServerName());
				resp.setHeader("Connection", "keep-alive");
				if (in != null) {
					if (isTranscoding) {
						resp.setHeader("Transfer-Encoding", "chunked");
						resp.setStatus(200);
					} else if (in.available() != len) {
						range.setEnd(range.getStart() + in.available());
						if (in.available() == 0) {
							len = range.getEnd() + 1;
						}
						resp.setHeader("Content-Range", "bytes " + range.getStart() + "-" + range.getEnd() + "/" + len);
						resp.setContentLength(in.available());
						resp.setStatus(206);
					} else {
						resp.setContentLength(in.available());
						resp.setStatus(200);
					}
					if (LOGGER.isTraceEnabled()) {
						logHttpServletResponse(req, resp, null, true);
					}
					renderer.start(item);
					if (sid != null) {
						item.setMediaSubtitle(sid);
					}
					OutputStream os = new BufferedOutputStream(resp.getOutputStream(), 512 * 1024);
					copyStreamAsync(in, os, async);
				} else {
					resp.setStatus(500);
					resp.setContentLength(0);
					async.complete();
				}
			}
		} catch (IOException ex) {
			return false;
		}
		return true;
	}

	/**
	 * Gets metadata from our database, which may be there from our API, for
	 * this HlsHelper, which could be a TV series, TV episode, or movie.
	 *
	 * @param resource
	 * @param isTVSeries whether this is a TV series, or an episode/movie
	 * @param renderer the renderer, used for looking up IDs
	 * @return a JsonObject to be used by a web browser which includes metadata
	 * names and when applicable, associated IDs, or null when there is no
	 * metadata
	 */
	private static JsonObject getMetadataAsJsonObject(StoreResource resource, Renderer renderer, String lang) {
		JsonObject result = null;
		if (resource instanceof MediaLibraryTvSeries mediaLibraryTvSeries) {
			TvSeriesMetadata tvSeriesMetadata = mediaLibraryTvSeries.getTvSeriesMetadata();
			result = tvSeriesMetadata.asJsonObject(lang);
		} else if (resource != null && resource.getMediaInfo() != null && resource.getMediaInfo().hasVideoMetadata()) {
			result = resource.getMediaInfo().getVideoMetadata().asJsonObject(lang);
		}
		if (result == null) {
			return null;
		}
		StoreResource actorsFolder = null;
		StoreResource countriesFolder = null;
		StoreResource directorsFolder = null;
		StoreResource genresFolder = null;
		StoreResource ratedFolder = null;
		if (CONFIGURATION.isShowMediaLibraryFolder()) {
			// prepare to get IDs of certain metadata resources, to make them clickable
			List<StoreResource> rootFolderChildren = renderer.getMediaStore().getResources("0", true, 0, 0, Messages.getString("MediaLibrary"));
			UMSUtils.filterResourcesByName(rootFolderChildren, Messages.getString("MediaLibrary"), true, true);
			if (rootFolderChildren.isEmpty()) {
				return null;
			}
			StoreResource mediaLibraryFolder = rootFolderChildren.get(0);
			List<StoreResource> mediaLibraryChildren = renderer.getMediaStore().getResources(mediaLibraryFolder.getId(), true, 0, 0, Messages.getString("Video"));
			UMSUtils.filterResourcesByName(mediaLibraryChildren, Messages.getString("Video"), true, true);
			StoreResource videoFolder = mediaLibraryChildren.get(0);

			boolean isRelatedToTV = resource instanceof MediaLibraryTvSeries || resource instanceof MediaLibraryTvEpisode;
			String folderName = isRelatedToTV ? Messages.getString("TvShows") : Messages.getString("Movies");
			List<StoreResource> videoFolderChildren = renderer.getMediaStore().getResources(videoFolder.getId(), true, 0, 0, folderName);
			UMSUtils.filterResourcesByName(videoFolderChildren, folderName, true, true);
			StoreResource tvShowsOrMoviesFolder = videoFolderChildren.get(0);

			List<StoreResource> tvShowsOrMoviesChildren = renderer.getMediaStore().getResources(tvShowsOrMoviesFolder.getId(), true, 0, 0, Messages.getString("FilterByInformation"));
			UMSUtils.filterResourcesByName(tvShowsOrMoviesChildren, Messages.getString("FilterByInformation"), true, true);
			StoreResource filterByInformationFolder = tvShowsOrMoviesChildren.get(0);

			List<StoreResource> filterByInformationChildren = renderer.getMediaStore().getResources(filterByInformationFolder.getId(), true, 0, 0, Messages.getString("Genres"));

			for (int filterByInformationChildrenIterator = 0; filterByInformationChildrenIterator < filterByInformationChildren.size(); filterByInformationChildrenIterator++) {
				StoreResource filterByInformationChild = filterByInformationChildren.get(filterByInformationChildrenIterator);
				switch (filterByInformationChild.getSystemName()) {
					case "Actors" -> {
						actorsFolder = filterByInformationChild;
					}
					case "Country" -> {
						countriesFolder = filterByInformationChild;
					}
					case "Director" -> {
						directorsFolder = filterByInformationChild;
					}
					case "Genres" -> {
						genresFolder = filterByInformationChild;
					}
					case "Rated" -> {
						ratedFolder = filterByInformationChild;
					}
					default -> {
						//nothing to do
					}
				}
			}
		}
		addJsonArrayDlnaIds(result, "actors", actorsFolder, renderer);
		addJsonArrayDlnaIds(result, "countries", countriesFolder, renderer);
		addJsonArrayDlnaIds(result, "directors", directorsFolder, renderer);
		addJsonArrayDlnaIds(result, "genres", genresFolder, renderer);
		addStringDlnaId(result, "rated", ratedFolder, renderer);
		result.addProperty("imageBaseURL", TMDB.getTmdbImageBaseURL());

		return result;
	}

	private static void addJsonArrayDlnaIds(final JsonObject object, final String memberName, final StoreResource folder, final Renderer renderer) {
		if (object.has(memberName)) {
			JsonElement element = object.remove(memberName);
			if (element.isJsonArray()) {
				JsonArray array = element.getAsJsonArray();
				if (!array.isEmpty()) {
					JsonArray dlnaChilds = new JsonArray();
					for (JsonElement child : array) {
						if (child.isJsonPrimitive()) {
							String value = child.getAsString();
							JsonObject dlnaChild = new JsonObject();
							dlnaChild.addProperty("name", value);
							if (folder != null) {
								List<StoreResource> folderChildren = renderer.getMediaStore().getResources(folder.getId(), true, 0, 0, value);
								UMSUtils.filterResourcesByName(folderChildren, value, true, true);
								if (!folderChildren.isEmpty()) {
									dlnaChild.addProperty("id", folderChildren.get(0).getId());
								}
							}
							dlnaChilds.add(dlnaChild);
						}
					}
					object.add(memberName, dlnaChilds);
				}
			}
		}
	}

	private static void addStringDlnaId(final JsonObject object, final String memberName, final StoreResource folder, final Renderer renderer) {
		if (object.has(memberName)) {
			JsonElement element = object.remove(memberName);
			if (element.isJsonPrimitive()) {
				String value = element.getAsString();
				JsonObject dlnaChild = new JsonObject();
				dlnaChild.addProperty("name", value);
				if (folder != null) {
					List<StoreResource> folderChildren = renderer.getMediaStore().getResources(folder.getId(), true, 0, 0, value);
					UMSUtils.filterResourcesByName(folderChildren, value, true, true);
					if (!folderChildren.isEmpty()) {
						dlnaChild.addProperty("id", folderChildren.get(0).getId());
					}
				}
				object.add(memberName, dlnaChild);
			}
		}
	}

	private static Long getLong(String value) {
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static ByteRange parseRange(HttpServletRequest req, long len) {
		String range = req.getHeader("Range");
		if (range == null || "".equals(range)) {
			return new ByteRange(0L, len);
		}
		String[] tmp = range.split("=")[1].split("-");
		long start = Long.parseLong(tmp[0]);
		long end = tmp.length == 1 ? len : Long.parseLong(tmp[1]);
		return new ByteRange(start, end);
	}

	/**
	 * Whether the MIME type is supported by all browsers. Note: This is a
	 * flawed approach because while browsers may support the container format,
	 * they may not support the codecs within. For example, most browsers
	 * support MP4 with H.264, but do not support it with H.265 (HEVC)
	 *
	 * @param mime
	 * @return
	 * @todo refactor to be more specific
	 */
	private static boolean directmime(String mime) {
		return mime != null &&
				(mime.equals(HTTPResource.MP4_TYPEMIME) ||
				mime.equals(HTTPResource.WEBM_TYPEMIME) ||
				mime.equals(HTTPResource.OGG_TYPEMIME) ||
				mime.equals(HTTPResource.AUDIO_M4A_TYPEMIME) ||
				mime.equals(HTTPResource.AUDIO_MP3_TYPEMIME) ||
				mime.equals(HTTPResource.AUDIO_OGA_TYPEMIME) ||
				mime.equals(HTTPResource.AUDIO_WAV_TYPEMIME) ||
				mime.equals(HTTPResource.BMP_TYPEMIME) ||
				mime.equals(HTTPResource.PNG_TYPEMIME) ||
				mime.equals(HTTPResource.JPEG_TYPEMIME) ||
				mime.equals(HTTPResource.GIF_TYPEMIME));
	}

}
