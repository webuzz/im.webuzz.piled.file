package im.webuzz.piled.file;

import java.util.List;

/**
 * This class is designed to hold file content for serving a file to
 * multiple requests which are targeting the same file.
 * 
 * @author zhourenjian
 *
 */
class HttpSharedFile {

	public boolean loaded;
	
	public byte[] content;
	
	public Object mutex;
	
	public List<Object> callbacks;

	public long lastModified;
	
}
