package im.webuzz.piled.file;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import im.webuzz.pilet.HttpConfig;
import im.webuzz.pilet.HttpLoggingUtils;
import im.webuzz.pilet.HttpRequest;
import im.webuzz.pilet.HttpResponse;
import im.webuzz.pilet.HttpWorkerUtils;

public class ZipResourcePilet extends StaticResourcePilet {
	@Override
	public boolean service(HttpRequest req, HttpResponse resp) {
		String serverBase = HttpFileConfig.serverBase;
		if (serverBase == null || serverBase.length() == 0) {
			// If serverBase is not set, this server will disable serving static resource.
			return false;
		}
		if (serviceRawResource(serverBase, req, resp, false)) {
			return true;
		}
		if (serviceRawResource(serverBase, req, resp, true)) {
			return true;
		}
		if (HttpFileConfig.page404 != null) {
			HttpFileUtils.send404NotFoundWithTemplate(req, resp);
			return true;
		}
		return false;
	}


	public static File getZipFileByURL(String serverBase, String host, String url) {
		if (serverBase == null || serverBase.length() == 0) {
			return null;
		}
		if (HttpRequest.isMaliciousHost(host)) {
			host = null; // Bad request
		}
		url = HttpRequest.fixURL(url);
		String zipURL = url.substring(0, url.lastIndexOf('/')) + ".zip";
		File file = null;
		if (host != null && host.length() > 0) {
			File hostFile = new File(serverBase + "/" + host + zipURL);
			if (hostFile.exists()) {
				file = hostFile;
			} else if (host.startsWith("www.")) {
				File domainFile = new File(serverBase + "/" + host.substring(4) + zipURL);
				if (domainFile.exists()) {
					file = domainFile;
				}
			}
		}
		if (file == null) {
			file = new File(serverBase + "/www" + zipURL);
		}
		return file;
	}

	/*
	 * Make method public static so other pilets can invoke this to service static resource files. 
	 */
	static boolean serviceRawResource(String serverBase, HttpRequest req, HttpResponse resp, boolean checkZipFile) {
		String url = req.url;
		if (url.endsWith("/")) {
			String pageIndex = HttpFileConfig.pageIndex;
			url = url + ((pageIndex == null || pageIndex.length() == 0) ? "index.html" : pageIndex); // we use index.html as the default index file.
		}
		File file = checkZipFile ? getZipFileByURL(serverBase, req.host, url) : HttpFileUtils.getFileByURL(serverBase, req.host, url);
		if (file == null) {
			return false;
		}
		if (!checkZipFile && file.isDirectory()) {
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
			return false;
		}
		if (file.exists()) {
			if (!checkZipFile) {
				int retCode = HttpFileUtils.serveStaticResource(req, resp, file, 0);
				HttpLoggingUtils.addLogging(req.host, req, resp, null, retCode, retCode == 304 ? 0 : file.length());
				return true;
			}
			// zip file
			ZipFile zipFile = null;
			try {
				zipFile = new ZipFile(file);
				String fileName = url.substring(url.lastIndexOf('/') + 1);
				if (fileName.length() == 0) {
					fileName = "index.html";
				}
				ZipEntry entry = zipFile.getEntry(fileName);
				if (entry == null) {
					int idx = req.url.lastIndexOf('/');
					if (idx != -1) {
						String name = req.url.substring(idx + 1);
						int dotIndex = name.lastIndexOf('.');
						if (dotIndex != -1) {
							String ext = name.substring(dotIndex + 1);
							if ("html".equalsIgnoreCase(ext) || "htm".equalsIgnoreCase(ext)) {
								return false; // 404
							}
						}
					}
					fileName += ".html";
					entry = zipFile.getEntry(fileName);
				}
				if (entry == null) {
					return false;
				}
				int retCode = serveZipResource(req, resp, zipFile, entry, 0);
				HttpLoggingUtils.addLogging(req.host, req, resp, null, retCode, retCode == 304 ? 0 : file.length());
				return true;
			} catch (ZipException e) {
				e.printStackTrace();
				return false;
			} catch (IOException e) {
				e.printStackTrace();
				return false;
			} finally {
				if (zipFile != null) {
					try {
						zipFile.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		} else if (req.url.endsWith("/")) {
			if (req.url.length() <= 1) { // server base folder may not exist
				return false;
			}
			// for URL .../folder/hello/, if there is no .../folder/hello/index.html, try .../folder/hello.html
			url = req.url.substring(0, req.url.length() - 1) + ".html";
			file = HttpFileUtils.getFileByURL(serverBase, req.host, url);
			if (file == null) {
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
						return false; // 404
					}
				}
				// URL .../folder/hello will be serving file .../folder/hello.html
				// Considering be compatible with Apache httpd
				url = req.url + ".html";
				file = HttpFileUtils.getFileByURL(serverBase, req.host, url);
				if (file == null) {
					return false;
				}
				if (file.exists() && file.isFile()) {
					int retCode = HttpFileUtils.serveStaticResource(req, resp, file, 0);
					HttpLoggingUtils.addLogging(req.host, req, resp, null, retCode, file.length());
					return true;
				}
			}
		}
		return false;
	}

	
	private static Object sharedFileMutex = new Object();
	private static Map<String, HttpSharedFile> sharedFileMap = new ConcurrentHashMap<String, HttpSharedFile>();

	public static int serveZipResource(final HttpRequest req,
			final HttpResponse resp, ZipFile zipFile, ZipEntry file, long expired) {
		//*
		if (req.lastModifiedSince > 0 && Math.abs(file.getTime() - req.lastModifiedSince) <= 1000) { // 1 second delta
			if (req.rangeBeginning >= 0 || req.rangeEnding >= 0) {
				// Google Chrome may come up with If-Modified-Since header and Range:bytes=0-0
				if (req.rangeBeginning == 0 && file.getSize() == req.rangeEnding - req.rangeBeginning + 1) {
					HttpWorkerUtils.send304NotModified(req, resp);
					return 304;
				}
			} else {
				HttpWorkerUtils.send304NotModified(req, resp);
				return 304;
			}
		}
		//*/
		StringBuilder responseBuilder = new StringBuilder(256);
		responseBuilder.append("HTTP/1.");
		responseBuilder.append(req.v11 ? '1' : '0');
		responseBuilder.append((req.rangeBeginning >= 0 || req.rangeEnding >= 0) ? " 206" : " 200");
		responseBuilder.append(" OK\r\n");
		String serverName = HttpConfig.serverSignature;
		if (req.requestCount < 1 && serverName != null && serverName.length() > 0) {
			responseBuilder.append("Server: ").append(serverName).append("\r\n");
		}
		boolean closeSocket = HttpWorkerUtils.checkKeepAliveHeader(req, responseBuilder);
		if (expired > 0) {
			responseBuilder.append("Expires: ");
			responseBuilder.append(HttpWorkerUtils.getStaticResourceExpiredDate(expired));
			responseBuilder.append("\r\n");
		} else {
			responseBuilder.append("Cache-Control: max-age=0\r\n");
		}
		responseBuilder.append("Date: ").append(HttpWorkerUtils.getHTTPDateString(System.currentTimeMillis()));
		responseBuilder.append("\r\nLast-Modified: ").append(HttpWorkerUtils.getHTTPDateString(file.getTime()));
		responseBuilder.append("\r\n");

		serveZipFile(req, resp, zipFile, file, responseBuilder);
		if (closeSocket) {
			resp.worker.poolingRequest(resp.socket, req);
		}
		return 200;
	}

	
	private static void serveZipFile(final HttpRequest req,
			final HttpResponse resp, ZipFile zipFile, ZipEntry file, StringBuilder responseBuilder) {
		String name = file.getName();
		String extName = name;
		int idx = name.lastIndexOf('.');
		if (idx != -1) {
			extName = name.substring(idx + 1);
		}
		String contentType = HttpWorkerUtils.getContentType(extName);
		if (contentType.startsWith("text/")) {
			contentType += "; charset=UTF-8";
		} else {
			req.supportGZip = false; // donot support other types beside html/css/javascript
		}
		int fileSize = (int) file.getSize(); // We do not support large file
		boolean toGZip = fileSize > HttpConfig.gzipStartingSize && req.supportGZip && contentType.startsWith("text/") && HttpWorkerUtils.isUserAgentSupportGZip(req.userAgent) && fileSize < HttpFileConfig.maxSingleResponse;
		boolean needCompressing = false;
		if (toGZip) {
			toGZip = false; // doesn't exist ...
			needCompressing = true;
		}
		
		if (toGZip || needCompressing) {
			responseBuilder.append("Content-Encoding: gzip\r\n");
		}
		responseBuilder.append("Access-Control-Allow-Origin: *\r\n");
		if (!(req.rangeBeginning >= 0 || req.rangeEnding >= 0)) {
			responseBuilder.append("Accept-Ranges: bytes\r\n");
		}
		if (!needCompressing) {
			responseBuilder.append("Content-Type: ");
			responseBuilder.append(contentType);
			responseBuilder.append("\r\n");
			if (!HttpWorkerUtils.checkContentLength(req, resp, fileSize, responseBuilder)) {
				responseBuilder.append("\r\n");
				byte[] data = responseBuilder.toString().getBytes();
				req.sending = data.length;
				if (resp.worker != null) resp.worker.getServer().send(resp.socket, data);
				return;
			}
			responseBuilder.append("\r\n");
			byte[] data = responseBuilder.toString().getBytes();
			//System.out.println(responseBuilder.toString());
			if ("HEAD".equals(req.method)) {
				req.sending = data.length;
				resp.worker.getServer().send(resp.socket, data);
			} else  if (fileSize > HttpFileConfig.maxSingleResponse || req.rangeBeginning >= 0 || req.rangeEnding >= 0) {
				// DO NOT support files bigger than 2G 
				// Need to avoid create large buffer for very large file
				//int headerSize = data.length;
				if (req.rangeBeginning >= 0 || req.rangeEnding >= 0) {
					if (req.rangeEnding >= 0) {
						fileSize = req.rangeEnding - req.rangeBeginning + 1; 
					} else {
						fileSize -= req.rangeBeginning; 
					}
				}
				req.sending = data.length + fileSize;
				resp.worker.getServer().send(resp.socket, data);
				
				int alreadySent = 0;
				InputStream fis = null;
				try {
					fis = zipFile.getInputStream(file);
					if (req.rangeBeginning > 0) {
						long skipped = fis.skip(req.rangeBeginning);
						while (skipped > 0 && skipped < req.rangeBeginning) {
							long thisSkipped = fis.skip(req.rangeBeginning - skipped);
							if (thisSkipped < 0) {
								break;
							}
							skipped += thisSkipped;
						}
					}
					while (alreadySent < fileSize) {
						byte[] buf = new byte[Math.min(HttpFileConfig.minSingleResponse, fileSize - alreadySent)];
						int read = -1;
						int count = 0;
						while ((read = fis.read(buf, count, buf.length - count)) != -1) {
							count += read;
							if (count >= buf.length) {
								break;
							}
						}
						resp.worker.getServer().send(resp.socket, buf);
						alreadySent += buf.length;
						if (read == -1) {
							break;
						}
						//*
//						if (req.sent < alreadySent + headerSize) {
//							long interval = 1000L * HttpConfig.minSingleResponse / HttpConfig.largeFileSpeedLimit;
//							try {
//								Thread.sleep(interval / 2); // average waiting time = interval time / 2
//							} catch (InterruptedException e) {
//								e.printStackTrace();
//							}
//							//if (req.closed > 0) {
//							//	break;
//							//}
//						}
						// check whether current request has been cancelled or interrupted
						if (req.closed > 0) {
							break;
						}
						//*/
					}
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					if (fis != null) {
						try {
							fis.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			} else {
				int bufferLength = fileSize + data.length;
				req.sending = bufferLength;
				
				byte[] buf = new byte[bufferLength];
				int read = -1;
				int offset = data.length;
				System.arraycopy(data, 0, buf, 0, offset);
				
				String filePath = req.url;
				HttpSharedFile attachedFileObj = null;
				HttpSharedFile mainFileObj = new HttpSharedFile();
				mainFileObj.mutex = new Object();
				mainFileObj.callbacks = new LinkedList<Object>();
				synchronized (sharedFileMutex) {
					attachedFileObj = sharedFileMap.get(filePath);
					if (attachedFileObj == null) {
						sharedFileMap.put(filePath, mainFileObj);
					}
				}
				if (attachedFileObj != null) {
					// there is another thread reading this file 
					if (attachedFileObj.loaded) {
						// Seldom reach this branch.
						// Only if main reading thread already loads file content but not
						// finishes notifying all existed waiting threads
						System.arraycopy(attachedFileObj.content, 0, buf, offset, fileSize);
						resp.worker.getServer().send(resp.socket, buf);
						return;
					} else {
						boolean isQueued = false;
						Object cb = new Object();
						synchronized (attachedFileObj.mutex) {
							if (!attachedFileObj.loaded && attachedFileObj.callbacks != null) {
								attachedFileObj.callbacks.add(cb);
								isQueued = true;
							}
						}
						if (isQueued) {
							try {
								synchronized (cb) {
									if (!attachedFileObj.loaded) {
										cb.wait();
									}
								}
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						} // else main thread already loads content but notifies no other threads
						if (attachedFileObj.loaded) {
							System.arraycopy(attachedFileObj.content, 0, buf, offset, fileSize);
							resp.worker.getServer().send(resp.socket, buf);
							return;
						} else {
							// continue to load file content
							synchronized (sharedFileMutex) {
								attachedFileObj = sharedFileMap.get(filePath);
								if (attachedFileObj == null) {
									sharedFileMap.put(filePath, mainFileObj);
								}
							}
						}
					}
				}
				InputStream fis = null;
				try {
					fis = zipFile.getInputStream(file);
					while ((read = fis.read(buf, offset, Math.min(bufferLength - offset, 8096))) > 0) {
						offset += read;
						if (offset >= bufferLength) {
							break;
						}
					}
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					if (fis != null) {
						try {
							fis.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
				resp.worker.getServer().send(resp.socket, buf);
				synchronized (sharedFileMutex) {
					sharedFileMap.remove(filePath);
				}
				byte[] content = null;
				boolean needNotify = mainFileObj.callbacks.size() > 0;
				if (needNotify) {
					content = new byte[fileSize];
					System.arraycopy(buf, data.length, content, 0, fileSize);
				}
				synchronized (mainFileObj.mutex) {
					if (needNotify || mainFileObj.callbacks.size() > 0) {
						if (content != null) {
							// already prepared outside synchronized block
							// so we can make this synchronized block more light-weighted
							mainFileObj.content = content;
						} else {
							mainFileObj.content = new byte[fileSize];
							System.arraycopy(buf, data.length, mainFileObj.content, 0, fileSize);
						}
						mainFileObj.loaded = true;
						for (Iterator<Object> itr = mainFileObj.callbacks.iterator(); itr.hasNext();) {
							Object cb = (Object) itr.next();
							synchronized (cb) {
								cb.notify();
							}
						}
					}
					mainFileObj.callbacks = null;
				}
			}
		} else {
			byte[] gzippedBytes = null;
			
			String filePath = req.url + ".gz";
			HttpSharedFile attachedFileObj = null;
			HttpSharedFile mainFileObj = new HttpSharedFile();
			mainFileObj.mutex = new Object();
			mainFileObj.callbacks = new LinkedList<Object>();
			synchronized (sharedFileMutex) {
				attachedFileObj = sharedFileMap.get(filePath);
				if (attachedFileObj == null) {
					sharedFileMap.put(filePath, mainFileObj);
				}
			}
			if (attachedFileObj != null) {
				// there is another thread reading this file 
				if (attachedFileObj.loaded) {
					// Seldom reach this branch.
					// Only if main reading thread already loads file content but not
					// finishes notifying all existed waiting threads
					gzippedBytes = attachedFileObj.content;
				} else {
					boolean isQueued = false;
					Object cb = new Object();
					synchronized (attachedFileObj.mutex) {
						if (!attachedFileObj.loaded && attachedFileObj.callbacks != null) {
							attachedFileObj.callbacks.add(cb);
							isQueued = true;
						}
					}
					if (isQueued) {
						try {
							synchronized (cb) {
								if (!attachedFileObj.loaded) {
									cb.wait();
								}
							}
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					} // else main thread already loads content but notifies no other threads
					if (attachedFileObj.loaded) {
						gzippedBytes = attachedFileObj.content;
					} else {
						// continue to load file content.
						synchronized (sharedFileMutex) {
							attachedFileObj = sharedFileMap.get(filePath);
							if (attachedFileObj == null) {
								sharedFileMap.put(filePath, mainFileObj);
							}
						}
					}
				}
			}

			if (gzippedBytes == null) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				GZIPOutputStream gZipOut = null;
				byte[] buf = new byte[8096];
				int read = -1;
				int count = 0;
				InputStream fis = null;
				try {
					gZipOut = new GZIPOutputStream(out);
					fis = zipFile.getInputStream(file);
					while ((read = fis.read(buf, 0, fileSize - count > 8096 ? 8096 : fileSize - count)) != -1) {
						count += read;
						gZipOut.write(buf, 0, read);
						if (count >= fileSize) {
							break;
						}
					}
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					if (fis != null) {
						try {
							fis.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
					if (gZipOut != null) {
						try {
							gZipOut.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
				gzippedBytes = out.toByteArray();
				
				synchronized (sharedFileMutex) {
					sharedFileMap.remove(filePath);
				}
				synchronized (mainFileObj.mutex) {
					if (mainFileObj.callbacks.size() > 0) {
						mainFileObj.content = gzippedBytes;
						mainFileObj.loaded = true;
						for (Iterator<Object> itr = mainFileObj.callbacks.iterator(); itr.hasNext();) {
							Object cb = (Object) itr.next();
							synchronized (cb) {
								cb.notify();
							}
						}
					}
					mainFileObj.callbacks = null;
				}
			}
			
			fileSize = gzippedBytes.length;
			responseBuilder.append("Content-Type: ");
			responseBuilder.append(contentType);
			responseBuilder.append("\r\n");
			if (!HttpWorkerUtils.checkContentLength(req, resp, fileSize, responseBuilder)) {
				responseBuilder.append("\r\n");
				//System.out.println(responseBuilder.toString());
				byte[] data = responseBuilder.toString().getBytes();
				req.sending = data.length;
				if (resp.worker != null) resp.worker.getServer().send(resp.socket, data);
				return;
			}
			responseBuilder.append("\r\n");
			//System.out.println(responseBuilder.toString());
			byte[] data = responseBuilder.toString().getBytes();
			//*
			int headerLength = data.length;
			if ("HEAD".equals(req.method)) {
				req.sending = data.length;
				resp.worker.getServer().send(resp.socket, data);
			} else if (req.rangeBeginning >= 0 || req.rangeEnding >= 0) {
				int rangedSize = fileSize;
				if (req.rangeEnding > 0) {
					rangedSize = req.rangeEnding - req.rangeBeginning + 1;
				} else {
					rangedSize = fileSize - req.rangeBeginning;
				}
				byte[] buffer = new byte[(int)(rangedSize + headerLength)];
				System.arraycopy(data, 0, buffer, 0, headerLength);
				System.arraycopy(gzippedBytes, req.rangeBeginning, buffer, headerLength, rangedSize);
				req.sending = buffer.length;
				resp.worker.getServer().send(resp.socket, buffer);
			} else {
				byte[] buffer = new byte[(int)(fileSize + headerLength)];
				System.arraycopy(data, 0, buffer, 0, headerLength);
				System.arraycopy(gzippedBytes, 0, buffer, headerLength, fileSize);
				req.sending = buffer.length;
				resp.worker.getServer().send(resp.socket, buffer);
			}
			//*/
			/*
			de.worker.getServer().send(de.socket, data);
			long interval = 3000;
			if (interval > 0) {
				try {
					Thread.sleep(interval);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			int offset = 0;
			for (int i = 0; i < (gzippedBytes.length + 8095) / 8096; i++) {
				if (i != 0 && interval > 0) {
					try {
						Thread.sleep(interval);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				int length = 8096;
				if (offset + 8096 > gzippedBytes.length) {
					length = gzippedBytes.length - offset;
				}
				byte[] buffer = new byte[length];
				System.arraycopy(gzippedBytes, offset, buffer, 0, length);
				de.worker.getServer().send(de.socket, buffer);
				offset += length;
				System.out.println("Sending " + file.getName() + " : " + length + ":" + offset + "/" + gzippedBytes.length);
			}
			//*/
		}
	}

}
