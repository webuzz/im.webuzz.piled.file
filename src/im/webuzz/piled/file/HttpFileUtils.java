package im.webuzz.piled.file;

import im.webuzz.pilet.HttpConfig;
import im.webuzz.pilet.HttpRequest;
import im.webuzz.pilet.HttpResponse;
import im.webuzz.pilet.HttpWorkerUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

public class HttpFileUtils {
	
	private static Object sharedFileMutex = new Object();
	private static Map<String, HttpSharedFile> sharedFileMap = new ConcurrentHashMap<String, HttpSharedFile>();

	public static void send404NotFoundWithTemplate(final HttpRequest req, final HttpResponse resp) {
		StringBuilder responseBuilder = new StringBuilder(256);
		responseBuilder.append("HTTP/1.");
		responseBuilder.append(req.v11 ? '1' : '0');
		responseBuilder.append(" 404 Not Found\r\n");
		String serverName = HttpConfig.serverSignature;
		if (req.requestCount < 1 && serverName != null && serverName.length() > 0) {
			responseBuilder.append("Server: ").append(serverName).append("\r\n");
		}
		boolean closeSocket = HttpWorkerUtils.checkKeepAliveHeader(req, responseBuilder);
		
		String baseLocalPath = null;
		String host = req.host;
        String serverBase = HttpFileConfig.serverBase;
		if (host != null && host.length() > 0
                && new File(serverBase + "/" + host).exists()) {
            baseLocalPath = serverBase + "/" + host;
        } else {
            baseLocalPath = serverBase + "/www";
        }
        File file = new File(baseLocalPath + "/404.html");
        if (!file.exists()) {
        	file = null;
        	String page404 = HttpFileConfig.page404;
			if (page404 != null && page404.length() > 0) {
        		file = new File(page404);
        		if (!file.exists()) {
        			file = null;
        		}
        	}
        }

		if (file != null) {
			serveFile(req, resp, file, responseBuilder);
		} else {
			responseBuilder.append("Content-Length: 0\r\n\r\n");
			byte[] data = responseBuilder.toString().getBytes();
			req.sending = data.length;
			resp.worker.getServer().send(resp.socket, data);
		}
		
		if (closeSocket) {
			resp.worker.poolingRequest(resp.socket, req);
		}
	}
	
	public static int serveStaticResource(final HttpRequest req,
			final HttpResponse resp, File file, long expired) {
		if (req.url.indexOf("../") != -1 || file.isDirectory() || !file.exists()) {
			// Normal browsers or normal clients should calculate to remove "..",
			// Or we think it is a hacking URL!
			HttpWorkerUtils.send404NotFound(req, resp);
			return 404;
			/*
			try {
				String path = file.getCanonicalPath();
				if (!path.startsWith(HttpConfig.serverBase)) {
					send404NotFound(req, de, closeSockets);
					return 404;
				}
			} catch (IOException e1) {
				e1.printStackTrace();
				send404NotFound(req, de, closeSockets);
				return 404;
			}
			//*/
		}
		//*
		if (req.lastModifiedSince > 0 && file.lastModified() <= req.lastModifiedSince + 1000) { // 1 second delta
			if (req.rangeBeginning >= 0 || req.rangeEnding >= 0) {
				// Google Chrome may come up with If-Modified-Since header and Range:bytes=0-0
				if (req.rangeBeginning == 0 && file.length() == req.rangeEnding - req.rangeBeginning + 1) {
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
		responseBuilder.append("\r\nLast-Modified: ").append(HttpWorkerUtils.getHTTPDateString(file.lastModified()));
		responseBuilder.append("\r\n");

		serveFile(req, resp, file, responseBuilder);
		if (closeSocket) {
			resp.worker.poolingRequest(resp.socket, req);
		}
		return 200;
	}

	
	private static void serveFile(final HttpRequest req,
			final HttpResponse resp, File file, StringBuilder responseBuilder) {
		String name = file.getName();
		int queryIdx = name.indexOf('?');
		if (queryIdx != -1) {
			name = name.substring(0, queryIdx);
		}
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
		int fileSize = (int) file.length(); // We do not support large file
		boolean toGZip = fileSize > HttpConfig.gzipStartingSize && req.supportGZip && contentType.startsWith("text/") && HttpWorkerUtils.isUserAgentSupportGZip(req.userAgent) && fileSize < HttpFileConfig.maxSingleResponse;
		boolean needCompressing = false;
		if (toGZip) {
			File gzFile = new File(file.getAbsolutePath() + ".gz");
			if (gzFile.exists() && gzFile.lastModified() > file.lastModified()) {
				file = gzFile;
			} else {
				toGZip = false; // doesn't exist ...
				needCompressing = true;
			}
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
				FileInputStream fis = null;
				try {
					fis = new FileInputStream(file);
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
				
				String filePath = file.getAbsolutePath();
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
				FileInputStream fis = null;
				try {
					fis = new FileInputStream(file);
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
			
			String filePath = file.getAbsolutePath() + ".gz";
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
				FileInputStream fis = null;
				try {
					gZipOut = new GZIPOutputStream(out);
					fis = new FileInputStream(file);
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
				System.out.println(responseBuilder.toString());
				byte[] data = responseBuilder.toString().getBytes();
				req.sending = data.length;
				if (resp.worker != null) resp.worker.getServer().send(resp.socket, data);
				return;
			}
			responseBuilder.append("\r\n");
			System.out.println(responseBuilder.toString());
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

	public static File getFileByURL(String serverBase, String host, String url) {
		if (serverBase == null || serverBase.length() == 0) {
			return null;
		}
		File file = null;
		if (HttpRequest.isMaliciousHost(host)) {
			host = null; // Bad request
		}
		url = HttpRequest.fixURL(url);
		if (host != null && host.length() > 0
				&& new File(serverBase + "/" + host).exists()) {
			file = new File(serverBase + "/" + host + url);
		} else {
			// folder www is for all hosts without a host folder
			file = new File(serverBase + "/www" + url);
		}
		return file;
	}

}
