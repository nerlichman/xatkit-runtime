package edu.uoc.som.jarvis.core;

import edu.uoc.som.jarvis.core.platform.RuntimePlatform;
import edu.uoc.som.jarvis.core.platform.action.RuntimeAction;
import edu.uoc.som.jarvis.core.platform.io.RuntimeEventProvider;
import edu.uoc.som.jarvis.core.recognition.IntentRecognitionProvider;
import edu.uoc.som.jarvis.core.recognition.IntentRecognitionProviderException;
import edu.uoc.som.jarvis.core.recognition.IntentRecognitionProviderFactory;
import edu.uoc.som.jarvis.core.server.JarvisServer;
import edu.uoc.som.jarvis.core.session.JarvisSession;
import edu.uoc.som.jarvis.core_resources.utils.LibraryLoaderUtils;
import edu.uoc.som.jarvis.core_resources.utils.PlatformLoaderUtils;
import edu.uoc.som.jarvis.execution.ActionInstance;
import edu.uoc.som.jarvis.execution.ExecutionModel;
import edu.uoc.som.jarvis.execution.ExecutionPackage;
import edu.uoc.som.jarvis.execution.ExecutionRule;
import edu.uoc.som.jarvis.intent.*;
import edu.uoc.som.jarvis.platform.ActionDefinition;
import edu.uoc.som.jarvis.platform.EventProviderDefinition;
import edu.uoc.som.jarvis.platform.PlatformDefinition;
import edu.uoc.som.jarvis.platform.PlatformPackage;
import edu.uoc.som.jarvis.util.Loader;
import fr.inria.atlanmod.commons.log.Log;
import org.apache.commons.configuration2.Configuration;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static fr.inria.atlanmod.commons.Preconditions.checkNotNull;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * The core component of the jarvis framework.
 * <p>
 * This class is constructed from an {@link ExecutionModel}, that defines the Intent to Action bindings that are
 * executed by the application. Constructing an instance of this class will load the {@link RuntimePlatform}s used by
 * the provided {@link ExecutionModel}, and enable the corresponding {@link RuntimeAction}s. It also creates an
 * instance of {@link EventDefinitionRegistry} that can be accessed to retrieve and manage {@link EventDefinition} .
 *
 * @see EventDefinitionRegistry
 * @see RuntimePlatformRegistry
 * @see ExecutionService
 * @see RuntimePlatform
 */
public class JarvisCore {

    /**
     * The {@link Configuration} key to store the {@link ExecutionModel} to use.
     *
     * @see #JarvisCore(Configuration)
     */
    public static String EXECUTION_MODEL_KEY = "jarvis.execution.model";

    /**
     * The {@link Configuration} key prefix to store the custom platform paths.
     * <p>
     * This prefix is used to specify the paths of the custom platforms that are needed by the provided
     * {@link ExecutionModel}. Note that custom platform path properties are only required if the
     * {@link ExecutionModel} defines an {@code alias} for the imported platform models. {@link ExecutionModel}s
     * relying on absolute paths are directly loaded from the file system, but are not portable.
     * <p>
     * Custom platform properties must be set following this pattern: {@code CUSTOM_PLATFORMS_KEY_PREFIX + <platform
     * alias> = <platform path>}.
     */
    public static String CUSTOM_PLATFORMS_KEY_PREFIX = "jarvis.platforms.custom.";

    /**
     * The {@link Configuration} key prefix to store the custom library paths.
     * <p>
     * This prefix is used to specify the paths of the custom libraries that are needed by the provided
     * {@link ExecutionModel}. Note that custom library path properties are only required if the
     * {@link ExecutionModel} defines an {@code alias} for the imported library models. {@link ExecutionModel}s
     * relying on absolute paths are directly loaded from the file system, but are not portable.
     * <p>
     * Custom library properties must be set following this pattern: {@code CUSTOM_LIBRARIES_KEY_PREFIX + <library
     * alias> = <library path>}.
     */
    public static String CUSTOM_LIBRARIES_KEY_PREFIX = "jarvis.libraries.custom.";

    /**
     * The {@link Configuration} used to initialize this class.
     * <p>
     * This {@link Configuration} is used to load and initialize platforms, see
     * {@link #loadRuntimePlatformFromPlatformModel(PlatformDefinition)} for more information on platform loading.
     *
     * @see #loadRuntimePlatformFromPlatformModel(PlatformDefinition)
     */
    private Configuration configuration;

    /**
     * The {@link IntentRecognitionProvider} used to compute {@link RecognizedIntent}s from input text.
     */
    private IntentRecognitionProvider intentRecognitionProvider;

    /**
     * The {@link RuntimePlatformRegistry} used to cache loaded {@link RuntimePlatform}, and provides utility method
     * to retrieve, unregister, and clear them.
     *
     * @see #getRuntimePlatformRegistry()
     */
    private RuntimePlatformRegistry runtimePlatformRegistry;

    /**
     * The {@link EventDefinitionRegistry} used to cache {@link EventDefinition}s and
     * {@link IntentDefinition}s from the input {@link ExecutionModel} and provides utility methods to retrieve
     * specific
     * {@link EventDefinition}s and {@link IntentDefinition}s and clear the cache.
     *
     * @see #getEventDefinitionRegistry() ()
     */
    private EventDefinitionRegistry eventDefinitionRegistry;

    /**
     * The {@link ResourceSet} used to load the {@link ExecutionModel} and the referenced models.
     * <p>
     * The {@code executionResourceSet} is initialized with the {@link #initializeExecutionResourceSet()}
     * method, that loads the core platforms models from the classpath, and dynamically retrieves the custom
     * platforms and library models.
     *
     * @see #CUSTOM_PLATFORMS_KEY_PREFIX
     * @see #CUSTOM_LIBRARIES_KEY_PREFIX
     */
    protected ResourceSet executionResourceSet;

    /**
     * The {@link ExecutionService} used to handle {@link EventInstance}s and execute the associated
     * {@link RuntimeAction}s.
     *
     * @see ExecutionService#handleEventInstance(EventInstance, JarvisSession)
     * @see RuntimeAction
     */
    protected ExecutionService executionService;

    /**
     * The {@link Map} used to store and retrieve {@link JarvisSession}s associated to users.
     *
     * @see #getOrCreateJarvisSession(String)
     */
    private Map<String, JarvisSession> sessions;

    /**
     * The {@link JarvisServer} instance used to capture incoming webhooks.
     */
    private JarvisServer jarvisServer;

    /**
     * Constructs a new {@link JarvisCore} instance from the provided {@code configuration}.
     * <p>
     * The provided {@code configuration} must provide values for the following key (note that additional values may
     * be required according to the used {@link RuntimeEventProvider}s and {@link RuntimePlatform}s):
     * <ul>
     * <li><b>jarvis.execution.model</b>: the {@link ExecutionModel} defining the Intent to
     * Action bindings (or the string representing its location)</li>
     * </ul>
     * <p>
     * The provided {@link ExecutionModel} defines the Intent to Action bindings that are executed by the
     * application. This constructor takes care of loading the {@link RuntimePlatform}s associated to the provided
     * {@link ExecutionModel} and enables the corresponding {@link RuntimeAction}s.
     * <p>
     * <b>Note:</b> the {@link RuntimePlatform}s associated to the provided {@link ExecutionModel} have to be
     * in the classpath in order to be dynamically loaded and instantiated.
     *
     * @param configuration the {@link Configuration} to construct the instance from
     * @throws NullPointerException if the provided {@code configuration} or one of the mandatory values is {@code null}
     * @throws JarvisException      if the framework is not able to retrieve the {@link ExecutionModel}
     * @see ExecutionModel
     */
    public JarvisCore(Configuration configuration) {
        checkNotNull(configuration, "Cannot construct a jarvis instance from a null configuration");
        try {
            this.configuration = configuration;
            this.executionResourceSet = initializeExecutionResourceSet();
            ExecutionModel executionModel = getExecutionModel(configuration.getProperty(EXECUTION_MODEL_KEY));
            checkNotNull(executionModel, "Cannot construct a %s instance from a null %s", this.getClass()
                    .getSimpleName(), ExecutionModel.class.getSimpleName());
            this.intentRecognitionProvider = IntentRecognitionProviderFactory.getIntentRecognitionProvider(this,
                    configuration);
            this.sessions = new HashMap<>();
            this.runtimePlatformRegistry = new RuntimePlatformRegistry();
            this.executionService = new ExecutionService(executionModel, runtimePlatformRegistry);
            this.eventDefinitionRegistry = new EventDefinitionRegistry();
            /*
             * Start the server before processing the EventProviderDefinitions, we need to have a valid JarvisServer
             * instance to call JarvisServer#registerWebhookEventProvider
             */
            this.jarvisServer = new JarvisServer(configuration);
            boolean intentRegistered = false;
            for (EventProviderDefinition eventProviderDefinition : executionModel.getEventProviderDefinitions()) {
                /*
                 * The EventProviderDefinition is still a proxy, meaning that the proxy resolution failed.
                 */
                if (eventProviderDefinition.eIsProxy()) {
                    throw new JarvisException(MessageFormat.format("An error occurred when resolving the proxy {0} " +
                            "from " +
                            "the {1}", eventProviderDefinition, ExecutionModel.class.getSimpleName()));
                }
                PlatformDefinition eventProviderPlatform = (PlatformDefinition) eventProviderDefinition.eContainer();
                RuntimePlatform eventProviderRuntimePlatform = this.runtimePlatformRegistry.getRuntimePlatform
                        (eventProviderPlatform.getName());
                if (isNull(eventProviderRuntimePlatform)) {
                    eventProviderRuntimePlatform = loadRuntimePlatformFromPlatformModel(eventProviderPlatform);
                    this.runtimePlatformRegistry.registerRuntimePlatform(eventProviderRuntimePlatform);
                }
                eventProviderRuntimePlatform.startEventProvider(eventProviderDefinition);
            }
            for (ExecutionRule rule : executionModel.getExecutionRules()) {
                /*
                 * We don't need to check whether the EventDefinition is a proxy, EventDefinitions are contained in
                 * EventProviderDefinitions, that have been checked before.
                 */
                EventDefinition eventDefinition = rule.getEvent();
                this.eventDefinitionRegistry.registerEventDefinition(eventDefinition);
                Log.info("Registering event {0}", eventDefinition.getName());
                if (eventDefinition instanceof IntentDefinition) {
                    IntentDefinition intentDefinition = (IntentDefinition) eventDefinition;
                    try {
                        this.intentRecognitionProvider.registerIntentDefinition(intentDefinition);
                        intentRegistered = true;
                    } catch (IntentRecognitionProviderException e) {
                        Log.warn(e.getMessage());
                    }
                }
                /*
                 * Load the action platforms
                 */
                for (ActionInstance actionInstance : rule.getActions()) {
                    ActionDefinition actionDefinition = actionInstance.getAction();
                    /*
                     * The Action is still a proxy, meaning that the proxy resolution failed.
                     */
                    if (actionDefinition.eIsProxy()) {
                        throw new JarvisException(MessageFormat.format("An error occurred when resolving the proxy " +
                                "{0} from the {1}", actionDefinition, ExecutionModel.class.getSimpleName()));
                    }
                    PlatformDefinition platform = (PlatformDefinition) actionDefinition.eContainer();
                    RuntimePlatform runtimePlatform = this.runtimePlatformRegistry.getRuntimePlatform(platform
                            .getName());
                    if (isNull(runtimePlatform)) {
                        runtimePlatform = loadRuntimePlatformFromPlatformModel(platform);
                        this.runtimePlatformRegistry.registerRuntimePlatform(runtimePlatform);
                    }
                    runtimePlatform.enableAction(actionDefinition);
                }
            }
            if (intentRegistered) {
                /*
                 * New intents have been registered in the IntentRecognitionProvider, we should explicitly ask the
                 * ML Engine to train in order to take them into account.
                 */
                intentRecognitionProvider.trainMLEngine();
            }
            jarvisServer.start();
            Log.info("Jarvis bot started");
        } catch (Throwable t) {
            Log.error("An error occurred when starting the {0}, trying to close started services", this.getClass()
                    .getSimpleName());
            stopServices();
            throw t;
        }
    }

    /**
     * Retrieves the {@link ExecutionModel} from the provided {@code property}.
     * <p>
     * This method checks if the provided {@code property} is already an in-memory {@link ExecutionModel}
     * instance, or if it is defined by a {@link String} or an {@link URI} representing the path of the model. In
     * that case, the method attempts to load the model at the provided location and returns it.
     * <p>
     * This method supports loading of model path defined by {@link String}s and {@link URI}s. Support for additional
     * types is planned in the next releases.
     *
     * @param property the {@link Object} representing the {@link ExecutionModel} to extract
     * @return the {@link ExecutionModel} from the provided {@code property}
     * @throws JarvisException      if the provided {@code property} type is not handled, if the
     *                              underlying {@link Resource} cannot be loaded or if it does not contain an
     *                              {@link ExecutionModel} top-level element, or if the loaded
     *                              {@link ExecutionModel} is empty.
     * @throws NullPointerException if the provided {@code property} is {@code null}
     */
    protected ExecutionModel getExecutionModel(Object property) {
        checkNotNull(property, "Cannot retrieve the %s from the property %s, please ensure it is " +
                        "set in the %s property of the jarvis configuration", ExecutionModel.class.getSimpleName(),
                property, EXECUTION_MODEL_KEY);
        if (property instanceof ExecutionModel) {
            return (ExecutionModel) property;
        } else {
            URI uri;
            if (property instanceof String) {
                uri = URI.createURI((String) property);
            } else if (property instanceof URI) {
                uri = (URI) property;
            } else {
                /*
                 * Unknown property type
                 */
                throw new JarvisException(MessageFormat.format("Cannot retrieve the {0} from the " +
                        "provided property {1}, the property type ({2}) is not supported", ExecutionModel.class
                        .getSimpleName(), property, property.getClass().getSimpleName()));
            }
            Resource executionModelResource;
            try {
                executionModelResource = executionResourceSet.getResource(uri, true);
            } catch (Exception e) {
                throw new JarvisException(MessageFormat.format("Cannot load the {0} at the given location: {1}",
                        ExecutionModel.class.getSimpleName(), uri.toString()), e);
            }
            if (isNull(executionModelResource)) {
                throw new JarvisException(MessageFormat.format("Cannot load the provided {0} (uri: {1})",
                        ExecutionModel.class.getSimpleName(), uri));
            }
            if (executionModelResource.getContents().isEmpty()) {
                throw new JarvisException(MessageFormat.format("The provided {0} is empty (uri: {1})", ExecutionModel
                        .class.getSimpleName(), executionModelResource.getURI()));
            }
            ExecutionModel executionModel;
            try {
                executionModel = (ExecutionModel) executionModelResource.getContents().get(0);
            } catch (ClassCastException e) {
                throw new JarvisException(MessageFormat.format("The provided {0} does not contain an " +
                                "{0} top-level element (uri: {1})", ExecutionModel.class.getSimpleName(),
                        executionModelResource.getURI()), e);
            }
            return executionModel;
        }
    }

    /**
     * Creates and initializes the {@link ResourceSet} used to load the provided {@link ExecutionModel}.
     * <p>
     * This method registers the Jarvis language's {@link EPackage}s and loads the <i>core platforms</i>
     * {@link Resource}s, <i>custom platforms</i> {@link Resource}s, and <i>custom libraries</i> {@link Resource}s in
     * the created {@link ResourceSet}.
     * <p>
     * <i>Core platforms</i> loading is done by searching in the classpath the {@code core_platforms/platforms/} folder,
     * and loads each {@code xmi} file it contains as a platform {@link Resource}. Note that this method loads
     * <b>all</b> the <i>core platforms</i> {@link Resource}s, even if they are not used in the application's
     * {@link ExecutionModel}.
     * <p>
     * <b>Note:</b> this method loads the {@code platforms/} folder from the {@code core_platforms} jar file if the
     * application is executed in a standalone mode. In a development environment (i.e. with all the project
     * sources imported) this method will retrieve the {@code platforms/} folder from the local installation, if it
     * exist.
     * <p>
     * <i>Custom platforms</i> loading is done by searching in the provided {@link Configuration} file the entries
     * starting with {@link #CUSTOM_PLATFORMS_KEY_PREFIX}. These entries are handled as absolute paths to the
     * <i>custom platform</i> {@link Resource}s, and are configured to be the target of the corresponding custom
     * platform proxies in the provided {@link ExecutionModel}.
     * <p>
     * <i>Custom libraries</i> loading is done by searching in the provided {@link Configuration} file the entries
     * starting with {@link #CUSTOM_LIBRARIES_KEY_PREFIX}. These entries are handled as absolute paths to the
     * <i>custom library</i> {@link Resource}s, and are configured to be the target of the corresponding custom
     * library proxies in the provided {@link ExecutionModel}.
     * <p>
     * This method ensures that the loaded {@link Resource}s corresponds to the {@code pathmaps} specified in the
     * provided {@code xmi} file. See
     * {@link LibraryLoaderUtils#CUSTOM_LIBRARY_PATHMAP}, {@link PlatformLoaderUtils#CORE_PLATFORM_PATHMAP} and
     * {@link PlatformLoaderUtils#CUSTOM_PLATFORM_PATHMAP} for further information.
     *
     * @throws JarvisException if an error occurred when loading the platform {@link Resource}s
     * @see PlatformLoaderUtils#CORE_PLATFORM_PATHMAP
     * @see PlatformLoaderUtils#CUSTOM_PLATFORM_PATHMAP
     * @see LibraryLoaderUtils#CUSTOM_LIBRARY_PATHMAP
     */
    private ResourceSet initializeExecutionResourceSet() {
        ResourceSet resourceSet = new ResourceSetImpl();
        resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl
                ());
        resourceSet.getPackageRegistry().put(ExecutionPackage.eNS_URI, ExecutionPackage.eINSTANCE);
        resourceSet.getPackageRegistry().put(IntentPackage.eNS_URI, IntentPackage.eINSTANCE);
        resourceSet.getPackageRegistry().put(PlatformPackage.eNS_URI, PlatformPackage.eINSTANCE);

        Log.info("Loading Jarvis core libraries");
        Path librariesPath = getPath("libraries/xmi/");
        try {
            Files.walk(librariesPath, 1).filter(l -> !Files.isDirectory(l)).forEach(libraryPath -> {
                try {
                    InputStream is = Files.newInputStream(libraryPath);
                    URI libraryPathmapURI = URI.createURI(LibraryLoaderUtils.CORE_LIBRARY_PATHMAP + libraryPath
                            .getFileName());

                    resourceSet.getURIConverter().getURIMap().put(libraryPathmapURI, URI.createURI(libraryPath
                            .getFileName().toString()));
                    Resource libraryResource = resourceSet.createResource(libraryPathmapURI);
                    libraryResource.load(is, Collections.emptyMap());
                    Library library = (Library) libraryResource.getContents().get(0);
                    is.close();
                    Log.info("Library {0} loaded", library.getName());
                } catch (IOException e) {
                    throw new JarvisException(MessageFormat.format("An error occurred when loading the library {0}, " +
                            "see attached exception", libraryPath), e);
                }
            });
        } catch (IOException e) {
            throw new JarvisException("An error occurred when crawling the core libraries, see attached exception", e);
        }

        Log.info("Loading Jarvis custom libraries");
        configuration.getKeys().forEachRemaining(key -> {
            if (key.startsWith(CUSTOM_LIBRARIES_KEY_PREFIX)) {
                String libraryPath = configuration.getString(key);
                String libraryName = key.substring(CUSTOM_LIBRARIES_KEY_PREFIX.length());
                URI libraryPathmapURI = URI.createURI(LibraryLoaderUtils.CUSTOM_LIBRARY_PATHMAP + libraryName);
                /*
                 * The provided path is handled as a File path. Loading custom libraries from external jars is left for
                 * a future release.
                 */
                File libraryFile = new File(libraryPath);
                if (libraryFile.exists() && libraryFile.isFile()) {
                    URI libraryFileURI = URI.createFileURI(libraryFile.getAbsolutePath());
                    resourceSet.getURIConverter().getURIMap().put(libraryPathmapURI, libraryFileURI);
                    Resource libraryResource = resourceSet.getResource(libraryFileURI, true);
                    Library library = (Library) libraryResource.getContents().get(0);
                    Log.info("Library {0} loaded", library.getName());
                } else {
                    throw new JarvisException(MessageFormat.format("Cannot load the custom library {0}, the provided" +
                            "path {0} is not a valid file", libraryPath));
                }
            }
            /*
             * The key is not a custom platform path, skipping it.
             */
        });

        Log.info("Loading Jarvis core platforms");
        Path platformsPath = getPath("platforms/xmi/");
        try {
            Files.walk(platformsPath, 1).filter(p -> !Files.isDirectory(p)).forEach(platformPath -> {
                try {
                    InputStream is = Files.newInputStream(platformPath);
                    URI platformPathmapURI = URI.createURI(PlatformLoaderUtils.CORE_PLATFORM_PATHMAP + platformPath
                            .getFileName());

                    resourceSet.getURIConverter().getURIMap().put(platformPathmapURI, URI.createURI(platformPath
                            .getFileName().toString()));
                    Resource platformResource = resourceSet.createResource(platformPathmapURI);
                    platformResource.load(is, Collections.emptyMap());
                    PlatformDefinition platform = (PlatformDefinition) platformResource.getContents().get(0);
                    is.close();
                    Log.info("Platform {0} loaded", platform.getName());
                } catch (IOException e) {
                    throw new JarvisException(MessageFormat.format("An error occurred when loading the platform {0}, " +
                            "see attached exception", platformPath), e);
                }
            });
        } catch (IOException e) {
            throw new JarvisException("An error occurred when crawling the core platforms, see attached exception", e);
        }

        Log.info("Loading Jarvis custom platforms");
        configuration.getKeys().forEachRemaining(key -> {
            if (key.startsWith(CUSTOM_PLATFORMS_KEY_PREFIX)) {
                String platformPath = configuration.getString(key);
                String platformName = key.substring(CUSTOM_PLATFORMS_KEY_PREFIX.length());
                URI platformPathmapURI = URI.createURI(PlatformLoaderUtils.CUSTOM_PLATFORM_PATHMAP + platformName);
                /*
                 * The provided path is handled as a File path. Loading custom platforms from external jars is left for
                 * a future release.
                 */
                File platformFile = new File(platformPath);
                if (platformFile.exists() && platformFile.isFile()) {
                    URI platformFileURI = URI.createFileURI(platformFile.getAbsolutePath());
                    resourceSet.getURIConverter().getURIMap().put(platformPathmapURI, platformFileURI);
                    Resource platformResource = resourceSet.getResource(platformFileURI, true);
                    PlatformDefinition platform = (PlatformDefinition) platformResource.getContents().get(0);
                    Log.info("Platform {0} loaded", platform.getName());
                } else {
                    throw new JarvisException(MessageFormat.format("Cannot load the custom platform {0}, the provided" +
                            "path {0} is not a valid file", platformPath));
                }
            }
            /*
             * The key is not a custom platform path, skipping it.
             */
        });
        return resourceSet;
    }

    /**
     * Computes the {@link Path} associated to the provided {@code resourceLocation}.
     * <p>
     * This method supports file system locations as well as locations within {@code jar} files from the classpath.
     * Computing the {@link Path} for a resource located in a {@code jar} file will initialize a dedicated
     * {@link FileSystem} enabling to navigate the {@code jar} contents.
     *
     * @param resourceLocation the location of the resource to retrieve the {@link Path} of
     * @return the computed {@link Path}
     * @throws JarvisException if an error occurred when computing the {@link Path}
     */
    private Path getPath(String resourceLocation) {
        URL url = this.getClass().getClassLoader().getResource(resourceLocation);
        java.net.URI uri;
        try {
            uri = url.toURI();
        } catch (URISyntaxException e) {
            throw new JarvisException(MessageFormat.format("An error occurred when loading the resource {0}, see " +
                    "attached exception", resourceLocation), e);
        }
        /*
         * Jarvis is imported as a jar, we need to setup a FileSystem that handles jar file loading.
         */
        if (uri.getScheme().equals("jar")) {
            try {
                /*
                 * Try to get the FileSystem if it exists, this may be the case if this method has been called to
                 * get the path of a resource stored in a jar file.
                 */
                FileSystems.getFileSystem(uri);
            } catch (FileSystemNotFoundException e) {
                Map<String, String> env = new HashMap<>();
                env.put("create", "true");
                try {
                    /*
                     * The FileSystem does not exist, try to create a new one with the provided URI. This is
                     * typically the case when loading a resource from a jar file for the first time.
                     */
                    FileSystems.newFileSystem(uri, env);
                } catch (IOException e1) {
                    throw new JarvisException(MessageFormat.format("An error occurred when loading the resource {0}, " +
                            "see attached exception", resourceLocation), e1);
                }
            }
        }
        return Paths.get(uri);
    }

    /**
     * Loads the {@link RuntimePlatform} defined by the provided {@link PlatformDefinition}.
     * <p>
     * This method searches in the classpath a {@link Class} matching the input
     * {@link PlatformDefinition#getRuntimePath()} value and calls its default constructor.
     *
     * @param platformDefinition the jarvis {@link PlatformDefinition} to load
     * @return an instance of the loaded {@link RuntimePlatform}
     * @throws JarvisException if their is no {@link Class} matching the provided {@code platformDefinition} or if the
     *                         {@link RuntimePlatform} can not be constructed
     * @see PlatformDefinition
     * @see RuntimePlatform
     */
    private RuntimePlatform loadRuntimePlatformFromPlatformModel(PlatformDefinition platformDefinition) throws
            JarvisException {
        Log.info("Loading RuntimePlatform {0}", platformDefinition.getName());
        Class<? extends RuntimePlatform> runtimePlatformClass = Loader.loadClass(platformDefinition.getRuntimePath(),
                RuntimePlatform.class);
        return Loader.constructRuntimePlatform(runtimePlatformClass, this, configuration);
    }

    /**
     * Returns the underlying {@link ExecutionService}.
     *
     * @return the underlying {@link ExecutionService}
     */
    public ExecutionService getExecutionService() {
        return this.executionService;
    }

    /**
     * Returns the underlying {@link IntentRecognitionProvider}.
     * <p>
     * <b>Note:</b> this method is designed to ease debugging and testing, direct interactions with the
     * {@link IntentRecognitionProvider} API may create consistency issues. In particular, jarvis does not ensure
     * that {@link RuntimeAction}s will be triggered in case of direct queries to the
     * {@link IntentRecognitionProvider} API.
     *
     * @return the underlying {@link IntentRecognitionProvider}
     */
    public IntentRecognitionProvider getIntentRecognitionProvider() {
        return intentRecognitionProvider;
    }

    /**
     * Returns the {@link EventDefinitionRegistry} associated to this instance.
     * <p>
     * This registry is used to cache {@link EventDefinition}s and {@link IntentDefinition}s from the input
     * {@link ExecutionModel} and provides utility methods to retrieve specific {@link EventDefinition} and
     * {@link IntentDefinition} and clear the cache.
     *
     * @return the {@link EventDefinitionRegistry} associated to this instance
     */
    public EventDefinitionRegistry getEventDefinitionRegistry() {
        return eventDefinitionRegistry;
    }

    /**
     * Returns the {@link RuntimePlatformRegistry} associated to this instance.
     * <p>
     * This registry is used to cache loaded {@link RuntimePlatform}s, and provides utility method to retrieve,
     * unregister, and clear them.
     *
     * @return the {@link RuntimePlatformRegistry} associated to this instance
     */
    public RuntimePlatformRegistry getRuntimePlatformRegistry() {
        return runtimePlatformRegistry;
    }

    /**
     * Returns the {@link JarvisServer} used to capture incoming webhooks.
     *
     * @return the {@link JarvisServer} used to capture incoming webhooks
     */
    public JarvisServer getJarvisServer() {
        return jarvisServer;
    }

    /**
     * Shuts down the {@link JarvisCore} and the underlying engines.
     * <p>
     * This method shuts down the underlying {@link IntentRecognitionProvider}, unloads and shuts down all the
     * {@link RuntimePlatform}s associated to this instance, unregisters the {@link EventDefinition} from the associated
     * {@link EventDefinitionRegistry}, shuts down the {@link ExecutionService}, and stops the {@link JarvisServer}.
     * <p>
     * <b>Note:</b> calling this method invalidates the {@link IntentRecognitionProvider} connection, and thus shuts
     * down intent recognition features. New {@link RuntimeAction}s cannot be processed either.
     *
     * @see IntentRecognitionProvider#shutdown()
     * @see RuntimePlatform#shutdown()
     * @see EventDefinitionRegistry#unregisterEventDefinition(EventDefinition)
     * @see ExecutionService#shutdown()
     * @see JarvisServer#stop()
     */
    public void shutdown() {
        Log.info("Shutting down JarvisCore");
        if (isShutdown()) {
            throw new JarvisException("Cannot perform shutdown, JarvisCore is already shutdown");
        }
        /* Shutdown the ExecutionService first in case there are running tasks using the IntentRecognitionProvider
         * API.
         */
        this.stopServices();
        Collection<RuntimePlatform> runtimePlatforms = this.getRuntimePlatformRegistry().getRuntimePlatforms();
        for (RuntimePlatform runtimePlatform : runtimePlatforms) {
            runtimePlatform.shutdown();
        }
        this.getRuntimePlatformRegistry().clearRuntimePlatforms();
        this.getEventDefinitionRegistry().clearEventDefinitions();
    }

    /**
     * Stops the running services.
     * <p>
     * This method does not throw any exception, but logs an error if an issue occurred when stoping a service.
     * Catching all the exception is done to attempt to stop all the services, but calling this method does not
     * ensure that all the services have been properly stopped.
     */
    private void stopServices() {
        /*
         * Catch each Throwable, if a service throw an error when closing we want to try to close the other ones.
         */
        if (nonNull(this.executionService)) {
            try {
                this.executionService.shutdown();
            } catch (Throwable t) {
                Log.error("An error occurred when closing the {0}", this.executionService.getClass().getSimpleName());
            }
        }
        if (nonNull(this.jarvisServer)) {
            try {
                this.jarvisServer.stop();
            } catch (Throwable t) {
                Log.error("An error occurred when closing the {0}", this.jarvisServer.getClass().getSimpleName());
            }
        }
        if (nonNull(this.intentRecognitionProvider)) {
            try {
                this.intentRecognitionProvider.shutdown();
            } catch (Throwable t) {
                Log.error("An error occurred when closing the {0}", this.intentRecognitionProvider.getClass()
                        .getSimpleName());
            }
        }
    }

    /**
     * Returns whether the {@link JarvisCore} client is shutdown.
     * <p>
     * This class is considered as shutdown if its underlying {@link ExecutionService},
     * {@link IntentRecognitionProvider}, and {@link JarvisServer} are shutdown.
     *
     * @return {@code true} if the {@link JarvisCore} client is shutdown, {@code false} otherwise
     */
    public boolean isShutdown() {
        return (!jarvisServer.isStarted()) && executionService.isShutdown() && intentRecognitionProvider
                .isShutdown();
    }

    /**
     * Retrieves or creates the {@link JarvisSession} associated to the provided {@code sessionId}.
     * <p>
     * If the {@link JarvisSession} does not exist a new one is created using
     * {@link IntentRecognitionProvider#createSession(String)}.
     *
     * @param sessionId the identifier to get or retrieve a session from
     * @return the {@link JarvisSession} associated to the provided {@code sessionId}
     * @throws NullPointerException if the provided {@code sessionId} is {@code null}
     */
    public JarvisSession getOrCreateJarvisSession(String sessionId) {
        checkNotNull(sessionId, "Cannot create or retrieve the %s from the provided session ID %s", JarvisSession
                .class.getSimpleName(), sessionId);
        JarvisSession session = getJarvisSession(sessionId);
        if (isNull(session)) {
            session = this.intentRecognitionProvider.createSession(sessionId);
            sessions.put(sessionId, session);
        }
        return session;
    }

    /**
     * Returns the {@link JarvisSession} associated to the provided {@code sessionId}
     *
     * @param sessionId the identifier to retrieve the session from
     * @return the {@link JarvisSession} associated to the provided {@code sessionId}
     * @throws NullPointerException if the provided {@code sessionId} is {@code null}
     */
    public JarvisSession getJarvisSession(String sessionId) {
        checkNotNull(sessionId, "Cannot retrieve a session from null as the session ID");
        return sessions.get(sessionId);
    }

    /**
     * Invalidates all the {@link JarvisSession}s and clear the session registry.
     */
    public void clearJarvisSessions() {
        this.sessions.clear();
    }

    /**
     * Logs a warning message and stops the running services if the {@link JarvisCore} hasn't been closed properly.
     *
     * @throws Throwable if an error occurred when stopping the running services.
     */
    @Override
    protected void finalize() throws Throwable {
        if (!this.isShutdown()) {
            Log.warn("{0} hasn't been shutdown properly, trying to stop running services");
            this.shutdown();
        }
        super.finalize();
    }
}
