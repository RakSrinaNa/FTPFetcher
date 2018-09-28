package fr.mrcraftcod.ftpfetcher;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;
import fr.mrcraftcod.utils.base.FileUtils;
import org.json.JSONObject;
import org.kohsuke.args4j.CmdLineParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Created by Thomas Couchoud (MrCraftCod - zerderr@gmail.com) on 09/12/2017.
 *
 * @author Thomas Couchoud
 * @since 2017-12-09
 */
public class Main{
	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
	private static final DateFormat outDateFormatter = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss");
	private static final DateTimeFormatter outDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss");
	private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ssZ");
	private static ExecutorService executor;
	
	public static void main(final String[] args) throws IOException, InterruptedException, ClassNotFoundException{
		final var lockFile = Paths.get(".ftpFetcher.lock").normalize().toAbsolutePath();
		if(lockFile.toFile().exists()){
			LOGGER.error("Program is already running, lock file {} is present", lockFile.toFile());
			System.exit(1);
		}
		touch(lockFile.toFile());
		lockFile.toFile().deleteOnExit();
		final var config = new Configuration();
		
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if(executor != null){
				executor.shutdownNow();
			}
			config.close();
		}));
		
		try{
			final var parameters = new Parameters();
			final var parser = new CmdLineParser(parameters);
			try{
				parser.parseArgument(args);
			}
			catch(final Exception ex){
				parser.printUsage(System.out);
				return;
			}
			
			Settings.getInstance(parameters.getProperties().getAbsolutePath());
			
			JSch.setConfig("StrictHostKeyChecking", "no");
			
			final var jsch = new JSch();
			final var knownHostsFilename = FileUtils.getHomeFolder(".ssh/known_hosts");
			jsch.setKnownHosts(knownHostsFilename.getAbsolutePath());
			
			LOGGER.info("Removed {} useless entries", config.removeUseless());
			
			final var startFetch = System.currentTimeMillis();
			final var downloadSet = new ConcurrentLinkedQueue<DownloadElement>();
			
			for(final var folderFetchObj : Settings.getArray("folders")){
				try{
					final var folderFetch = (JSONObject) folderFetchObj;
					final var connection = new FTPConnection(jsch);
					downloadSet.addAll(fetchFolder(config, connection, folderFetch.getString("ftpFolder"), Paths.get(new File(".").toURI()).resolve(folderFetch.getString("localFolder")), folderFetch.getBoolean("recursive")));
					connection.close();
				}
				catch(final JSchException | IOException | SftpException e){
					var folder = ((JSONObject) folderFetchObj).getString("ftpFolder");
					if(e.getMessage().equals("No such file")){
						LOGGER.warn("Folder {} doesn't exist", folder);
					}
					else{
						LOGGER.error("Error fetching folder {}", folder, e);
					}
				}
				catch(final Exception e){
					LOGGER.error("Error fetching folder {}", folderFetchObj, e);
				}
			}
			LOGGER.info("Found {} elements to download in {}ms", downloadSet.size(), System.currentTimeMillis() - startFetch);
			
			LOGGER.info("Starting with {} downloaders", parameters.getThreadCount());
			
			final var startDownload = System.currentTimeMillis();
			executor = Executors.newFixedThreadPool(parameters.getThreadCount());
			List<Future<List<DownloadResult>>> futures = new ArrayList<>();
			
			try{
				futures = IntStream.range(0, parameters.getThreadCount()).mapToObj(i -> new FTPFetcher(jsch, config, downloadSet)).map(executor::submit).collect(Collectors.toList());
			}
			catch(final Exception e){
				LOGGER.error("Error building fetchers", e);
			}
			
			executor.shutdown();
			final var results = futures.parallelStream().map(f -> {
				try{
					return f.get();
				}
				catch(InterruptedException | ExecutionException e){
					LOGGER.error("Error waiting for fetcher", e);
				}
				return null;
			}).filter(Objects::nonNull).flatMap(Collection::stream).collect(Collectors.toList());
			
			final var downloadedSuccessfully = results.stream().filter(DownloadResult::isDownloaded).collect(Collectors.toList());
			
			LOGGER.info("Downloaded {}/{} elements ({}) in {} (avg: {})", downloadedSuccessfully.size(), results.size(), org.apache.commons.io.FileUtils.byteCountToDisplaySize(downloadedSuccessfully.stream().mapToLong(r -> r.getElement().getFile().getAttrs().getSize()).sum()), Duration.ofMillis(System.currentTimeMillis() - startDownload), Duration.ofMillis((long) downloadedSuccessfully.stream().mapToLong(DownloadResult::getDownloadTime).average().orElse(0L)));
		}
		catch(final Exception e){
			LOGGER.error("Uncaught exception", e);
		}
		System.exit(0);
	}
	
	private static Collection<? extends DownloadElement> fetchFolder(final Configuration config, final FTPConnection connection, final String folder, final Path outPath, final boolean recursive) throws SftpException, InterruptedException{
		LOGGER.info("Fetching folder {}", folder);
		final var array = connection.getClient().ls(folder).toArray();
		LOGGER.info("Fetched folder {}, {} elements found, verifying them", folder, array.length);
		return config.getOnlyNotDownloaded(folder, Arrays.stream(array).map(o -> (ChannelSftp.LsEntry) o).collect(Collectors.toList())).stream().sorted(Comparator.comparing(ChannelSftp.LsEntry::getFilename)).filter(f -> {
			if(f.getFilename().equals(".") || f.getFilename().equals("..")){
				return false;
			}
			if(f.getAttrs().isDir()){
				return true;
			}
			return true;
		}).flatMap(f -> {
			try{
				if(recursive && f.getAttrs().isDir()){
					return fetchFolder(config, connection, folder + (folder.endsWith("/") ? "" : "/") + f.getFilename() + "/", outPath.resolve(f.getFilename()), true).stream();
				}
				return Stream.of(downloadFile(folder, f, outPath.toFile()));
			}
			catch(Exception e){
				LOGGER.error("Error fetching folder {}", f.getLongname(), e);
			}
			return null;
		}).filter(Objects::nonNull).collect(Collectors.toList());
	}
	
	private static void touch(final File file) throws IOException{
		if(!file.exists()){
			new FileOutputStream(file).close();
		}
	}
	
	private static DownloadElement downloadFile(final String folder, final ChannelSftp.LsEntry file, final File folderOut){
		final String date;
		final var datePart = file.getFilename().substring(0, file.getFilename().lastIndexOf("."));
		try{
			if(datePart.chars().allMatch(Character::isDigit)){
				date = outDateFormatter.format(new Date(Long.parseLong(datePart) * 1000));
			}
			else{
				date = OffsetDateTime.from(dateTimeFormatter.parse(datePart)).format(outDateTimeFormatter);
			}
		}
		catch(final NumberFormatException e){
			LOGGER.error("Error parsing filename {}", datePart);
			return null;
		}
		final var fileOut = new File(folderOut, date + file.getFilename().substring(file.getFilename().lastIndexOf(".")));
		if(fileOut.exists()){
			return null;
		}
		FileUtils.createDirectories(fileOut);
		
		return new DownloadElement(folder, file, fileOut);
	}
}
