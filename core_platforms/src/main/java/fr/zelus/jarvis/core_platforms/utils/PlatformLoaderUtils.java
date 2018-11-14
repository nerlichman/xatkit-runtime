package fr.zelus.jarvis.core_platforms.utils;

/**
 * An interface providing {@code pathmap} utility methods to dynamically load modules.
 */
public interface PlatformLoaderUtils {

    /**
     * The {@code pathmap} prefix used to map core platform resources to their concrete URIs.
     * <p>
     * References to core platforms can be built using the following code: {@code URI.createURI(CORE_PLATFORM_PATHMAP +
     * "MyModule.xmi"}. The JarvisCore engine will take care of dynamically loading the platforms and mapping their
     * {@code pathmaps} to the concrete resources.
     */
    String CORE_PLATFORM_PATHMAP = "pathmap://JARVIS_CORE_PLATFORM/";

    /**
     * The {@code pathmap} prefix used to map custom platforms resources to their concrete URIs.
     * <p>
     * References to custom platforms can be built using the following code : {@code URI.createURI
     * (CUSTOM_PLATFORM_PATHMAP + "MyModule.xmi"}. The JarvisCore engine will take care of dynamically loading the
     * platforms and mapping their {@code pathmaps} to the concrete resources.
     * <p>
     * Custom platform resource concrete paths must be specified in the Jarvis configuration using {@code
     * JarvisCore#CUSTOM_PLATFORM_KEY_PREFIX + <module_name>=<concrete_path>}.
     */
    String CUSTOM_PLATFORM_PATHMAP = "pathmap://JARVIS_CUSTOM_PLATFORM/";
}
