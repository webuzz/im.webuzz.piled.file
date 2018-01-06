package im.webuzz.piled.file;

public class HttpFileConfig {

	/**
	 * Server resources base path. We calculate other paths, like ROOT path,
	 * based on this path.
	 * 
	 * If serverBase is not set or set to null or empty, static resource files
	 * won't be served.
	 */
	public static String serverBase;

	/**
	 * Global 404 page path for the server. If not being set, a blank page
	 * will be returned.
	 * 404 not found page can be customized by placing 404.html under host
	 * root folder, e.g. /t/piled/ROOT/helloworld.com/404.html 
	 */
	public static String page404;
	
	/**
	 * Default index page. By default (not set), it will be index.html.
	 */
	public static String pageIndex;
	
	/*
	 * Configurations for serving large file.
	 * 
	 * Piled server is not optimized for serving large file. We recommend
	 * using other HTTP server, like Apache httpd, for serving large file.
	 * 
	 * The following configurations are here for protecting server from
	 * being frozen by accidently putting a very large file on server. 
	 */
	/**
	 * In serving large file, we set a speed limit to avoid being blocked
	 * by a connection downloading a large file.
	 */
	public static int largeFileSpeedLimit = 1024 * 1024; // 1MB/s
	/**
	 * Read a block of a large file into memory before sending.
	 */
	public static int minSingleResponse = 64 * 1024; // 64k
	/**
	 * Only file larger than given limit will be treated as large file.
	 */
	public static int maxSingleResponse = 512 * 1024; // 512k = 8 * 64k
	/*
	 * Configurations for server large file end.
	 */
	
}
