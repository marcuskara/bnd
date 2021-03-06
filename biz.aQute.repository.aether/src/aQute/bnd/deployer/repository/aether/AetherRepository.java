package aQute.bnd.deployer.repository.aether;

import static aQute.bnd.deployer.repository.RepoConstants.*;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;

import org.apache.maven.repository.internal.*;
import org.eclipse.aether.*;
import org.eclipse.aether.artifact.*;
import org.eclipse.aether.connector.basic.*;
import org.eclipse.aether.deployment.*;
import org.eclipse.aether.impl.*;
import org.eclipse.aether.impl.DefaultServiceLocator.ErrorHandler;
import org.eclipse.aether.repository.*;
import org.eclipse.aether.repository.RemoteRepository.Builder;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.spi.connector.*;
import org.eclipse.aether.spi.connector.transport.*;
import org.eclipse.aether.transfer.*;
import org.eclipse.aether.transfer.TransferEvent.RequestType;
import org.eclipse.aether.transport.file.*;
import org.eclipse.aether.transport.http.*;
import org.eclipse.aether.util.repository.*;

import aQute.bnd.deployer.repository.*;
import aQute.bnd.osgi.*;
import aQute.bnd.service.*;
import aQute.bnd.version.*;
import aQute.lib.io.*;
import aQute.service.reporter.*;

@aQute.bnd.annotation.plugin.BndPlugin(name="aether", parameters=AetherRepository.Config.class)
public class AetherRepository implements Plugin, RegistryPlugin, RepositoryPlugin, IndexProvider {

	interface Config {
		String name();
		URI url();
		URI indexUrl();
		String username();
		String password();
		String cache();
	}
	public static final String PROP_NAME = "name";
	public static final String PROP_MAIN_URL = "url";
	public static final String PROP_INDEX_URL = "indexUrl";
	public static final String PROP_USERNAME = "username";
	public static final String PROP_PASSWORD = "password";
	public static final String PROP_CACHE = "cache";

	private static final String	META_OBR	= ".meta/obr.xml";

	Reporter reporter;
	private Registry registry;
	
	// Config Properties
	private String name = this.getClass().getSimpleName();
	private URI mainUri;
	private URI indexUri;
	private File cacheDir = new File(System.getProperty("user.home")+ File.separator + DEFAULT_CACHE_DIR);
	private String username = null;
	private String password = "";

	// Initialisation Fields
	private boolean initialised;
	private RepositorySystem repoSystem;
	private RemoteRepository remoteRepo;
	private LocalRepository localRepo;
	private FixedIndexedRepo indexedRepo;

	@Override
	public void setReporter(Reporter reporter) {
		this.reporter = reporter;
	}
	
	@Override
	public void setRegistry(Registry registry) {
		this.registry = registry;
	}
	
	@Override
	public void setProperties(Map<String, String> props) throws Exception {
		// Read name property
		if (props.containsKey(PROP_NAME))
			name = props.get(PROP_NAME);

		// Read main Nexus URL property
		String mainUrlStr = props.get(PROP_MAIN_URL);
		if (mainUrlStr == null)
			throw new IllegalArgumentException(String.format("Attribute '%s' must be set on '%s' plugin.", PROP_MAIN_URL, getClass().getName()));
		try {
			if (mainUrlStr.endsWith("/"))
				mainUrlStr = mainUrlStr.substring(0, mainUrlStr.length() - 1);
			mainUri = new URI(mainUrlStr);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(String.format("Invalid '%s' property in plugin %s", PROP_MAIN_URL), e);
		}

		// Read index URL property if present
		String indexUriStr = props.get(PROP_INDEX_URL);
		if (indexUriStr == null) {
			indexUri = findDefaultVirtualIndexUri(mainUri);
		} else {
			try {
				indexUri = new URI(indexUriStr);
			} catch (URISyntaxException e) {
				throw new IllegalArgumentException(String.format("Invalid '%s' property in plugin %s", PROP_INDEX_URL), e);
			}
		}

		// Read username and password
		if (props.containsKey(PROP_USERNAME))
			username = props.get(PROP_USERNAME);
		if (props.containsKey(PROP_PASSWORD))
			password = props.get(PROP_PASSWORD);

		// Read cache path property
		String cachePath = props.get(PROP_CACHE);
		if (cachePath != null) {
			cacheDir = new File(cachePath);
			if (!cacheDir.isDirectory()) {
				String canonicalPath;
				try {
					canonicalPath = cacheDir.getCanonicalPath();
				} catch (IOException e) {
					throw new IllegalArgumentException(String.format("Could not canonical path for cacheDir '%s'.", cachePath), e);
				}
				throw new IllegalArgumentException(String.format("Cache path '%s' does not exist, or is not a directory", canonicalPath));
			}
		}
	}
	
	protected final synchronized void init() throws Exception {
		if (initialised)
			return;

		// Initialise Aether
		DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
		locator.addService(ArtifactDescriptorReader.class, DefaultArtifactDescriptorReader.class);
		locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
		locator.addService(TransporterFactory.class, FileTransporterFactory.class);
		locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
		locator.setErrorHandler(new ErrorHandler() {
			@Override
			public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
				if (reporter != null)
					reporter.error("Service creation failed for type %s using impl %s: %s", type, impl, exception.getLocalizedMessage());
				exception.printStackTrace();
			}
		});
		repoSystem = locator.getService(RepositorySystem.class);
		if (repoSystem == null)
			throw new IllegalArgumentException("Failed to initialise Aether repository system");
		
		Builder builder = new RemoteRepository.Builder("remote", "default", mainUri.toString());
		if (username != null) {
			AuthenticationBuilder authBuilder = new AuthenticationBuilder().addUsername(username);
			if (password != null)
				authBuilder.addPassword(password);
			builder.setAuthentication(authBuilder.build());
		}
		remoteRepo = builder.build();
		localRepo = new LocalRepository(new File(cacheDir, "aether-local"));
		
		// Initialise Index
		if (indexUri == null) {
			indexedRepo = null;
		} else {
			// Test whether the index URI exists and is available.
			HttpURLConnection connection = (HttpURLConnection) indexUri.toURL().openConnection();
			try {
				connection.setRequestMethod("HEAD");
				int responseCode = connection.getResponseCode();
				if (responseCode >= 400) {
					indexedRepo = null;
				} else {
					indexedRepo = new FixedIndexedRepo();
					Map<String, String> config = new HashMap<String, String>();
					indexedRepo.setReporter(this.reporter);
					indexedRepo.setRegistry(registry);
					
					config.put(FixedIndexedRepo.PROP_CACHE, cacheDir.getAbsolutePath());
					config.put(FixedIndexedRepo.PROP_LOCATIONS, indexUri.toString());
					indexedRepo.setProperties(config);
				}
			}
			catch (UnknownHostException e) {
				return;
			}
			finally {
				connection.disconnect();
			}
		}

		initialised = true;
	}
	
	@Override
	public PutResult put(InputStream stream, PutOptions options) throws Exception {
		init();

		DigestInputStream digestStream = new DigestInputStream(stream, MessageDigest.getInstance("SHA-1"));
		final File tmpFile = IO.createTempFile(cacheDir, "put", ".bnd");
		try {
			IO.copy(digestStream, tmpFile);
			byte[] digest = digestStream.getMessageDigest().digest();
			
			if (options.digest != null && !Arrays.equals(options.digest, digest))
				throw new IOException("Retrieved artifact digest doesn't match specified digest");
			
			// Get basic info about the bundle we're deploying
			Jar jar = new Jar(tmpFile);
			Artifact artifact = ConversionUtils.fromBundleJar(jar);
			artifact.setFile(tmpFile);
	
			// Setup the Aether repo session and create the deployment request
			DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
			session.setLocalRepositoryManager(repoSystem.newLocalRepositoryManager(session, localRepo));
			final DeployRequest request = new DeployRequest();
			request.addArtifact(artifact);
			request.setRepository(remoteRepo);
			
			// Capture the result including remote resource URI
			final ResultHolder resultHolder = new ResultHolder();
			session.setTransferListener(new AbstractTransferListener() {
				@Override
				public void transferSucceeded(TransferEvent event) {
					TransferResource resource = event.getResource();
					if (event.getRequestType() == RequestType.PUT && tmpFile.equals(resource.getFile())) {
						PutResult result = new PutResult();
						result.artifact = URI.create(resource.getRepositoryUrl() + resource.getResourceName());
						resultHolder.result = result;
						System.out.println("UPLOADED to: " + URI.create(resource.getRepositoryUrl() + resource.getResourceName()));
					}
				}
				@Override
				public void transferFailed(TransferEvent event) {
					if (event.getRequestType() == RequestType.PUT && tmpFile.equals(event.getResource().getFile()))
						resultHolder.error = event.getException();
				}
				@Override
				public void transferCorrupted(TransferEvent event) throws TransferCancelledException {
					if (event.getRequestType() == RequestType.PUT && tmpFile.equals(event.getResource().getFile()))
						resultHolder.error = event.getException();
				}
			});
			
			// Do the deploy and report results
			repoSystem.deploy(session, request);

			if (resultHolder.result != null) {
				if (indexedRepo != null)
					indexedRepo.reset();

				return resultHolder.result;
			} else if (resultHolder.error != null) {
				throw new Exception("Error during artifact upload", resultHolder.error);
			} else {
				throw new Exception("Artifact was not uploaded");
			}
		} finally {
			if (tmpFile != null && tmpFile.isFile())
				IO.delete(tmpFile);
		}
	}

	@Override
	public List<String> list(String pattern) throws Exception {
		init();

		// only supported with a valid index
		return indexedRepo != null ? indexedRepo.list(pattern) : null;
	}
	
	@Override
	public SortedSet<Version> versions(String bsn) throws Exception {
		init();

		// Use the index by preference
		if (indexedRepo != null)
			return indexedRepo.versions(ConversionUtils.maybeMavenCoordsToBsn(bsn));

		Artifact artifact = null;

		try {
			artifact = new DefaultArtifact(bsn + ":[0,)");
		}
		catch (Exception e) {
			// ignore non-GAV style dependencies
		}

		if (artifact == null)
			return null;

		// Setup the Aether repo session and create the range request
		DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
		session.setLocalRepositoryManager(repoSystem.newLocalRepositoryManager(session, localRepo));

		VersionRangeRequest rangeRequest = new VersionRangeRequest();
		rangeRequest.setArtifact(artifact);
		rangeRequest.setRepositories(Collections.singletonList(remoteRepo));
		
		// Resolve the range
		VersionRangeResult rangeResult = repoSystem.resolveVersionRange(session, rangeRequest);
		
		// Add to the result
		SortedSet<Version> versions = new TreeSet<Version>();
		for (org.eclipse.aether.version.Version version : rangeResult.getVersions()) {
			try {
				versions.add(MvnVersion.parseString(version.toString()).getOSGiVersion());
			}
			catch (IllegalArgumentException e) {
				// ignore version
			}
		}
		return versions;
	}
	
	@Override
	public File get(String bsn, Version version, Map<String, String> properties, DownloadListener... listeners) throws Exception {
		init();
		
		// Use the index by preference
		if (indexedRepo != null)
			return indexedRepo.get(ConversionUtils.maybeMavenCoordsToBsn(bsn), version, properties, listeners);
		
		File file = null;
		boolean getSource = false;
		try {
			// Setup the Aether repo session and request
			DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
			session.setLocalRepositoryManager(repoSystem.newLocalRepositoryManager(session, localRepo));

			if (bsn.endsWith(".source")) {
				String originalBsn = properties.get("bsn");

				if (originalBsn != null) {
					bsn = originalBsn;
					getSource = true;
				}
			}

			String[] coords = ConversionUtils.getGroupAndArtifactForBsn(bsn);

			MvnVersion mvnVersion = new MvnVersion(version);

			String versionStr = null;

			if ("exact".equals(properties.get("strategy")) || getSource) {
				versionStr = properties.get("version");
			}
			else {
				versionStr = mvnVersion.toString();
			}

			Artifact artifact = null;

			if (getSource) {
				artifact = new DefaultArtifact(coords[0], coords[1], "sources", "jar", versionStr);
			}
			else {
				artifact = new DefaultArtifact(coords[0], coords[1], "jar", versionStr);
			}

			ArtifactRequest request = new ArtifactRequest();
			request.setArtifact(artifact);
			request.setRepositories(Collections.singletonList(remoteRepo));

			// Log the transfer
			session.setTransferListener(new AbstractTransferListener() {
				@Override
				public void transferStarted(TransferEvent event) throws TransferCancelledException {
					System.err.println(event);
				}
				@Override
				public void transferSucceeded(TransferEvent event) {
					System.err.println(event);
				}
				@Override
				public void transferFailed(TransferEvent event) {
					System.err.println(event);
				}
			});
			
			try {
				// Resolve the version
				ArtifactResult artifactResult = repoSystem.resolveArtifact(session, request);
				artifact = artifactResult.getArtifact();
				file = artifact.getFile();
			}
			catch (ArtifactResolutionException ex) {
				// could not download artifact, simply return null
			}
			
			return file;
		} finally {
			for (DownloadListener dl : listeners) {
				if (file != null)
					dl.success(file);
				else
					dl.failure(null, "Download failed");
			}
		}
	}

	@Override
	public boolean canWrite() {
		return true;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getLocation() {
		return "http://localhost:8080/nexus/content/repositories/scratch1";
	}
	
	/**
	 * <p>
	 * Find the default URI of the OBR index for a hosted repository, assuming
	 * that the OBR plugin is used to generate a Virtual repository with the
	 * same name as the referenced repository with the addition of "-obr" to its
	 * name.
	 * </p>
	 * <p>
	 * For example suppose there is a hosted repository with the identity
	 * "releases"; it will have the URI
	 * {@code http://hostname/nexus/content/repositories/releases}. We assume
	 * there is a Virtual OBR repository with ID "releases-obr". It will have
	 * the URL {@code http://hostname/nexus/content/repositories/releases-obr}, and
	 * the OBR index will be at
	 * {@code http://hostname/nexus/content/repositories/releases-obr/.meta/obr.xml}.
	 * 
	 * @param hostedUri
	 *            The URI of the source Hosted repository.
	 * @return
	 */
	private static URI findDefaultVirtualIndexUri(URI hostedUri) {
		StringBuilder sb = new StringBuilder();
		sb.append(hostedUri.getScheme());
		sb.append("://");
		sb.append(hostedUri.getHost());

		if (hostedUri.getPort() > 0)
			sb.append(":" + hostedUri.getPort());

		String path = hostedUri.getPath();

		if (path != null && path.endsWith("/"))
			path = path.substring(0, path.length() - 1);

		if (path == null || path.length() == 0)
			sb.append("/");
		else
			sb.append(path);
	
		return URI.create(sb.toString() + "-obr/" + META_OBR);
	}

	@Override
	public List<URI> getIndexLocations() throws Exception {
		init();
		return indexedRepo != null ? indexedRepo.getIndexLocations() : Collections.<URI>emptyList();
	}
	
	@Override
	public Set<ResolutionPhase> getSupportedPhases() {
		return EnumSet.allOf(ResolutionPhase.class);
	}
	
	static class ResultHolder {
		RepositoryPlugin.PutResult result;
		Exception error;
	}

}
