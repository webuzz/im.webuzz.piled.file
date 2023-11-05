/*******************************************************************************
 * Copyright (c) 2010 - 2011 webuzz.im
 *
 * Author:
 *   Zhou Renjian / zhourenjian@gmail.com - initial API and implementation
 *******************************************************************************/

package im.webuzz.piled.file;

import im.webuzz.pilet.HttpLoggingUtils;
import im.webuzz.pilet.HttpRequest;
import im.webuzz.pilet.HttpResponse;
import im.webuzz.pilet.HttpWorkerUtils;
import im.webuzz.pilet.IPilet;

import java.io.File;

/**
 * Default pilet for static resources.
 * 
 * @author zhourenjian
 *
 */
public class StaticResourcePilet implements IPilet {

	@Override
	public boolean service(HttpRequest req, HttpResponse resp) {
		String serverBase = HttpFileConfig.serverBase;
		if (serverBase == null || serverBase.length() == 0) {
			// If serverBase is not set, this server will disable serving static resource.
			return false;
		}
		return serviceStaticResource(serverBase, req, resp);
	}

	/*
	 * Make method public static so other pilets can invoke this to service static resource files. 
	 */
	public static boolean serviceStaticResource(String serverBase, HttpRequest req, HttpResponse resp) {
		String url = req.url;
		if (url.endsWith("/")) {
			String pageIndex = HttpFileConfig.pageIndex;
			url = url + ((pageIndex == null || pageIndex.length() == 0) ? "index.html" : pageIndex); // we use index.html as the default index file.
		}
		File file = HttpFileUtils.getFileByURL(serverBase, req.host, url);
		if (file == null) {
			if (HttpFileConfig.page404 != null) {
				HttpFileUtils.send404NotFoundWithTemplate(req, resp);
				return true;
			}
			return false;
		}
		if (file.isDirectory()) {
			String pageIndex = HttpFileConfig.pageIndex;
			File indexFile = new File(file, ((pageIndex == null || pageIndex.length() == 0) ? "index.html" : pageIndex)); // we use index.html as the default index file.
			if (indexFile.exists() && indexFile.isFile()) {
				int retCode = HttpFileUtils.serveStaticResource(req, resp, indexFile, 0);
				HttpLoggingUtils.addLogging(req.host, req, resp, null, retCode, retCode == 304 ? 0 : indexFile.length());
				return true;
			}
			File htmlFile = new File(file.getAbsolutePath() + ".html"); // we use ###.html as the default index file.
			if (htmlFile.exists() && htmlFile.isFile()) {
				int retCode = HttpFileUtils.serveStaticResource(req, resp, htmlFile, 0);
				HttpLoggingUtils.addLogging(req.host, req, resp, null, retCode, retCode == 304 ? 0 : htmlFile.length());
				return true;
			}
			/*
			// redirect to URL ending with "/" for existed folder
			HttpWorkerUtils.redirect((resp.worker.getServer().isSSLEnabled() ? "https://" : "http://") + req.host + req.url + "/", req, resp);
			HttpLoggingUtils.addLogging(req.host, req, 301, 0);
			return true;
			*/
			if (HttpFileConfig.page404 != null) {
				HttpFileUtils.send404NotFoundWithTemplate(req, resp);
				return true;
			}
			return false;
		}
		if (file.exists()) {
			int retCode = HttpFileUtils.serveStaticResource(req, resp, file, 0);
			HttpLoggingUtils.addLogging(req.host, req, resp, null, retCode, retCode == 304 ? 0 : file.length());
			return true;
		} else if (req.url.endsWith("/")) {
			if (req.url.length() <= 1) { // server base folder may not exist
				if (HttpFileConfig.page404 != null) {
					HttpFileUtils.send404NotFoundWithTemplate(req, resp);
					return true;
				}
				return false;
			}
			// for URL .../folder/hello/, if there is no .../folder/hello/index.html, try .../folder/hello.html
			url = req.url.substring(0, req.url.length() - 1) + ".html";
			file = HttpFileUtils.getFileByURL(serverBase, req.host, url);
			if (file == null) {
				if (HttpFileConfig.page404 != null) {
					HttpFileUtils.send404NotFoundWithTemplate(req, resp);
					return true;
				}
				return false;
			}
			if (file.exists() && file.isFile()) {
				int retCode = HttpFileUtils.serveStaticResource(req, resp, file, 0);
				HttpLoggingUtils.addLogging(req.host, req, resp, null, retCode, file.length());
				return true;
			} else {
				url = req.url.substring(0, req.url.length() - 1);
				file = HttpFileUtils.getFileByURL(serverBase, req.host, url);
				if (file == null) {
					if (HttpFileConfig.page404 != null) {
						HttpFileUtils.send404NotFoundWithTemplate(req, resp);
						return true;
					}
					return false;
				}
				if (file.exists() && file.isFile()) {
					// try file .../folder/hello for URL .../folder/hello/
					HttpWorkerUtils.redirect((resp.worker.getServer().isSSLEnabled() ? "https://" : "http://") + req.host + url, req, resp);
					HttpLoggingUtils.addLogging(req.host, req, resp, null, 301, 0);
					return true;
				}
			}
		} else {
			int idx = req.url.lastIndexOf('/');
			if (idx != -1) {
				String name = req.url.substring(idx + 1);
				int dotIndex = name.lastIndexOf('.');
				if (dotIndex != -1) {
					String ext = name.substring(dotIndex + 1);
					if ("html".equalsIgnoreCase(ext) || "htm".equalsIgnoreCase(ext)) {
						if (HttpFileConfig.page404 != null) {
							HttpFileUtils.send404NotFoundWithTemplate(req, resp);
							return true;
						}
						return false; // 404
					}
				}
				// URL .../folder/hello will be serving file .../folder/hello.html
				// Considering be compatible with Apache httpd
				url = req.url + ".html";
				file = HttpFileUtils.getFileByURL(serverBase, req.host, url);
				if (file == null) {
					if (HttpFileConfig.page404 != null) {
						HttpFileUtils.send404NotFoundWithTemplate(req, resp);
						return true;
					}
					return false;
				}
				if (file.exists() && file.isFile()) {
					int retCode = HttpFileUtils.serveStaticResource(req, resp, file, 0);
					HttpLoggingUtils.addLogging(req.host, req, resp, null, retCode, file.length());
					return true;
				}
			}
		}
		if (HttpFileConfig.page404 != null) {
			HttpFileUtils.send404NotFoundWithTemplate(req, resp);
			return true;
		}
		return false;
	}

}
