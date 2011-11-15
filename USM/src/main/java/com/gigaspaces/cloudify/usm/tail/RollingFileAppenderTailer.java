package com.gigaspaces.cloudify.usm.tail;

import java.io.File;
import java.io.FilenameFilter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * tail a RollingFileAppender logs folder without interfering with the RFA rolling action.
 * in-order to avoid locking the file and by that preventing the RFA from rolling the file,
 * this tailer will sample all files periodically without opening the files and when finding that
 * a file has been modified, only then open the file, "grab" the newly added lines and close
 * the file when done.
 *    
 * @author adaml
 *
 */
public class RollingFileAppenderTailer implements Runnable{
	
	/*********
	 * Handler interface which allows a client to delegate the handling of new lines found by the tailer to a custom class.
	 * @author barakme
	 *
	 */
	public static interface LineHandler {
		
		/****************
		 * Called when a new line is found 
		 * @param line
		 */
		public void handleLine(final String fileName, final String line);
	}
	
	private class DefaultLineHandler implements LineHandler {

		@Override
		public void handleLine(final String fileName, final String line) {
			logger.info(line);
			
		}
		
	}

	private static final String BREAK_BY_LINES_NO_EMPTY_LINES_REGEX = "\\r?\\n+";
	private static final int DEFAULT_SAMPLING_DELAY = 2000;
	private String logsDirectory;
	private String regex;
	private Pattern lineSplitPattern = Pattern.compile(BREAK_BY_LINES_NO_EMPTY_LINES_REGEX);

	private Map<String, RollingFileReader> logFileMap = new HashMap<String, RollingFileReader>(); 

	private static java.util.logging.Logger logger = java.util.logging.Logger
	.getLogger(RollingFileAppenderTailer.class.getName());

	private LineHandler handler = new DefaultLineHandler();
	
	/**
	 * Create a new RollingFileAppenderTailer given the file-name regex and the 
	 * directory where the log files will be located.
	 * 
	 * @param dir - the path to the directory of the log files to be tailed.
	 * @param regex - regular expression for file names to be tailed.
	 */
	public RollingFileAppenderTailer(String dir, String regex){
		this.logsDirectory = dir;
		this.regex = regex;
	}
	
	/****************
	 * Creates a new Tailer with a callback that handles each new line.
	 * 
	 * @param dir - the path to the directory of the log files to be tailed.
	 * @param regex - regular expression for file names to be tailed.
	 * @param handler - the callback that handles new lines.
	 */
	public RollingFileAppenderTailer(String dir, String regex, final LineHandler handler){
		this.logsDirectory = dir;
		this.regex = regex;
		this.handler = handler;
	}

	/**
	 * Create a new RollingFileAppenderTailer given the file-name regex, the 
	 * directory where the log files will be saved and the time period between
	 * sampling the files.
	 * 
	 * @param dir - the path to the directory of the log files to be tailed.
	 * @param regex - regular expression for file names to be tailed.
	 * @param samplingDelay - the time delay between sampling of files.
	 */
	public RollingFileAppenderTailer(String dir, String regex, long samplingDelay){
		this.logsDirectory = dir;
		this.regex = regex;
	}

	/**
	 * Start a new tailer on a predetermined folder. 
	 * @param directory - logs directory to be tailed.
	 * @param regex - expected log file name format. 
	 */
	public static void start(String directory, String regex){
		ScheduledExecutorService executor;
		executor = Executors.newScheduledThreadPool(1);
		executor.scheduleWithFixedDelay(new RollingFileAppenderTailer(directory, regex), 0, DEFAULT_SAMPLING_DELAY, TimeUnit.MILLISECONDS);

	}

	/**
	 * Start a new tailer on a predetermined folder.
	 * @param directory - logs directory to be tailed.
	 * @param regex - expected log file name format. 
	 * @param samplingDelay
	 */
	public static void start(String directory, String regex, long samplingDelay){
		ScheduledExecutorService executor;
		executor = Executors.newScheduledThreadPool(1);
		executor.scheduleWithFixedDelay(new RollingFileAppenderTailer(directory, regex), 0, samplingDelay, TimeUnit.MILLISECONDS);
	}

	@Override
	public void run() {
		try{
			getLogFilesMap(logFileMap);
			for (String key : logFileMap.keySet()) {
				if (logFileMap.get(key).wasModified()){
					String lines = logFileMap.get(key).readLines();
					String seporatedLines[] = lineSplitPattern.split(lines);
					for (String line : seporatedLines) {
						handler.handleLine(key, line);
						//logger.info(line);
					}
				}
			}

		}catch(Exception e){
			logger.warning("Exception thrown: " + e.getMessage());
		}

	}

	/**
	 * Scans the folder for new files added to the logs folder.
	 * If a new file that is not contained in the map is found,
	 * it is added to the map. If a file no longer exists, it will
	 * be taken out of the map.
	 * @param logFileList
	 */
	private void getLogFilesMap(Map<String, RollingFileReader> logFileMap) {

		File folder = new File(logsDirectory);
		//Get list of files according to regex.
		File[] files = folder.listFiles(new FilenameFilter(){
			@Override
			public boolean accept(File dir, String name){
				return java.util.regex.Pattern.matches(regex, name);
			}
		});

		//add newly created files if exist. 
		for (File file : files) {
			if (!logFileMap.containsKey(file.getName())){
				logFileMap.put(file.getName(), new RollingFileReader(file));
			}
		}
		
		//remove files that no longer exist.
		Iterator<RollingFileReader> iterator = logFileMap.values().iterator();
		while(iterator.hasNext()){
			RollingFileReader next = iterator.next();
			if (!next.exists()){
				iterator.remove();
			}
		}
	}
}
