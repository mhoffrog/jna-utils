/**
 *
 */
package com.mhoffrog.jna.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jna.Platform;
import com.sun.jna.platform.linux.LibC;
import com.sun.jna.platform.win32.Kernel32;

/**
 * Utility class to unpack and load dynamic libraries from a JAR file on the
 * class path.
 *
 * @author mhoffrog
 *
 */
public class NativeUtils {

	private static final Logger LOGGER = Logger.getLogger(NativeUtils.class.getName());

	// Workaround: init the sequence of dependent libraries to load
	// Note: This WO became obsolete due to
	// Kernel32.INSTANCE.SetEnvironmentVariable("PATH",...) works for Windows
	// private static Map<String, String[]> mapDependentLibs = new HashMap<>();
	// static {
	// mapDependentLibs.put("win32-x86", new String[] { "libwinpthread-1",
	// "libgcc_s_dw2-1", "unicorn" });
	// mapDependentLibs.put("win32-x86-64", new String[] { "libwinpthread-1",
	// "libgcc_s_seh-1", "unicorn" });
	// }

	private static final String LD_PATH_ENV_NAME;
	static {
		if (Platform.isWindows()) {
			LD_PATH_ENV_NAME = "PATH";
		} else {
			LD_PATH_ENV_NAME = "LD_LIBRARY_PATH";
		}
	}

	private NativeUtils() {
		// Not supposed to instantiated
	}

	/**
	 * @param packagedClass
	 *            - the class contained in packaged jar file, where the native
	 *            libraries are to be loaded from.<br>
	 * @param homeSubDirName
	 *            - the subdirectory in the user.home directory, wher to copy the
	 *            native libraries into
	 * @throws RuntimeException
	 */
	public static void extractNativeLibsToUserHome(final Class<?> packagedClass, final String homeSubDirName)
			throws RuntimeException {
		final Package p = packagedClass.getPackage();
		String version = p.getImplementationVersion();
		boolean isOverrideExisting = true;
		if (version == null) {
			// throw new RuntimeException("ERROR: implementation version is missing in the
			// package!");
			version = "unknownVersion";
		} else {
			isOverrideExisting = version.endsWith("-SNAPSHOT");
		}
		final String targetDirName = System.getProperty("user.home")
				+ (homeSubDirName != null && homeSubDirName.length() > 0 ? File.separatorChar + homeSubDirName : "")
				+ File.separatorChar + version + File.separatorChar + Platform.RESOURCE_PREFIX;
		copyResourceLibsToTargetDir(packagedClass, isOverrideExisting, targetDirName);
		extendJavaLibraryPathAndLDPath(targetDirName);
		// Workaround - not needed yet - left commented in case needed for another OS
		// loadDependentLibraries();
	}

	/**
	 * Extracts jar bundled files from folder
	 * <package-root>/{@link Platform#RESOURCE_PREFIX} to directory
	 * <code>targetDirName</code>.
	 *
	 * @param packagedClass
	 *            - the class contained in packaged jar file, where the native
	 *            libraries are to be loaded from.<br>
	 *            - native libraries are to be located in drirectory
	 *            <package-root>/{@link Platform#RESOURCE_PREFIX}
	 * @param isOverrideExisting
	 *            - if true, target files exiting will be overwritten
	 * @param targetDirName
	 *            - the target directory - will be created, if not existing
	 * @throws RuntimeException
	 */
	public static void copyResourceLibsToTargetDir(final Class<?> packagedClass, final boolean isOverrideExisting,
			final String targetDirName) throws RuntimeException {
		final File targetDir = new File(targetDirName);
		if (!targetDir.exists()) {
			if (!targetDir.mkdirs()) {
				throw new RuntimeException("ERROR: Directory " + targetDirName + " could not be created!");
			}
		}
		final List<URL> listOfLibFileURLs = getListOfLibFileURLs(packagedClass);
		for (final URL libURL : listOfLibFileURLs) {
			final String libURLStr = libURL.toString();
			final String libFileName = libURLStr.substring(libURLStr.lastIndexOf("/") + 1);
			final String targetFileName = targetDirName + File.separatorChar + libFileName;
			copyResourceLib(libURL, targetFileName, isOverrideExisting);
		}
	}

	/**
	 * Prepends <code>pathName</code> to the java.library.path and to the OS dynamic
	 * library load path variable.
	 *
	 * @param pathName
	 *            the path name to be prepended to the java.library.path and the OS
	 *            dynamic library load path variable
	 * @throws RuntimeException
	 */
	public static void extendJavaLibraryPathAndLDPath(final String pathName) throws RuntimeException {
		String sysLDPath = System.getProperty("java.library.path");
		sysLDPath = sysLDPath != null ? pathName + File.pathSeparatorChar + sysLDPath : pathName;
		System.setProperty("java.library.path", sysLDPath);
		// set sys_paths to null so that java.library.path will be reevaluated next time
		// found at
		// https://stackoverflow.com/questions/15409223/adding-new-paths-for-native-libraries-at-runtime-in-java
		try {
			final Field sysPathsField = ClassLoader.class.getDeclaredField("sys_paths");
			sysPathsField.setAccessible(true);
			sysPathsField.set(null, null);
		} catch (final Throwable t) {
			throw new RuntimeException("ERROR: Failed to update java.library.path!", t);
		}
		// extend the OS specific PATH variable as well
		// Note: currently only Windows and Linux/Unix OS are supported
		sysLDPath = null;
		if (Platform.isWindows()) {
			final int size = Kernel32.INSTANCE.GetEnvironmentVariable(LD_PATH_ENV_NAME, null, 0);
			if (size > 0) {
				final char[] pathChars = new char[size];
				Kernel32.INSTANCE.GetEnvironmentVariable(LD_PATH_ENV_NAME, pathChars, pathChars.length);
				sysLDPath = new String(pathChars);
			}
		} else if (Platform.isX11()) {
			sysLDPath = LibC.INSTANCE.getenv(LD_PATH_ENV_NAME);
		}
		sysLDPath = sysLDPath != null ? pathName + File.pathSeparatorChar + sysLDPath : pathName;
		if (Platform.isWindows()) {
			if (!Kernel32.INSTANCE.SetEnvironmentVariable(LD_PATH_ENV_NAME, sysLDPath)) {
				throw new RuntimeException("Kernel32.SetEnvironmentVariable(\"" + LD_PATH_ENV_NAME + "\") failed!");
			}
		} else if (Platform.isX11()) {
			if (0 != LibC.INSTANCE.setenv(LD_PATH_ENV_NAME, sysLDPath, 1)) {
				throw new RuntimeException("LibC.setenv(\"" + LD_PATH_ENV_NAME + "\") failed!");
			}
		}
	}

	private static void copyResourceLib(final URL sourceURL, final String targetFileName,
			final boolean isOverrideExisting) {
		if (!isOverrideExisting && new File(targetFileName).exists()) {
			return;
		}
		try (InputStream ins = sourceURL.openStream()) {
			try {
				Files.copy(ins, Paths.get(targetFileName), StandardCopyOption.REPLACE_EXISTING);
				if (isOverrideExisting) {
					System.out.println(" -> copied resource=" + sourceURL + " to file=" + targetFileName);
				}
			} catch (final IOException e) {
				throw new RuntimeException("ERROR: Failed to copy resource=" + sourceURL + " to file=" + targetFileName,
						e);
			}
		} catch (final IOException e) {
			throw new RuntimeException("ERROR: Failed to read resource=" + sourceURL, e);
		}
	}

	private static List<URL> getListOfLibFileURLs(final Class<?> packagedClass) {
		final List<URL> ret = new ArrayList<>();
		try {
			// final Enumeration<URL> en =
			// packagedClass.getClassLoader().getResources(Platform.RESOURCE_PREFIX);
			final String classResourceName = packagedClass.getName().replaceAll("\\.", "/") + ".class";
			final Enumeration<URL> en = packagedClass.getClassLoader().getResources(classResourceName);
			int i = 0;
			while (en.hasMoreElements()) {
				i++;
				URL url = en.nextElement();
				if (i > 1) {
					LOGGER.log(Level.WARNING, "Class " + packagedClass.getName() + " found in multiple resources: "
							+ url + " will be ignored for loading related native libs!");
					continue;
				}
				final String rootUrlStr = url.toString().replace(classResourceName, "");
				url = new URL(rootUrlStr);
				if (url.getProtocol().equals("file")) {
					final File[] listOfFiles;
					try {
						listOfFiles = new File(new URL(rootUrlStr + Platform.RESOURCE_PREFIX).toURI()).listFiles();
					} catch (final URISyntaxException e) {
						throw new RuntimeException("ERROR: URI conversion of url path=" + rootUrlStr
								+ Platform.RESOURCE_PREFIX + " failed!", e);
					}
					if (listOfFiles != null) {
						for (final File f : listOfFiles) {
							if (f.isFile()) {
								ret.add(f.toURI().toURL());
							} else {
								LOGGER.log(Level.WARNING, "Resources in sub directory=" + f.toPath()
										+ " are ignored for loading native libs!");
							}
						}
					}
				} else if (url.getProtocol().equals("jar")) {
					try {
						final JarURLConnection urlcon = (JarURLConnection) url.openConnection();
						final String pathPrefix = Platform.RESOURCE_PREFIX + "/";
						try (JarFile jar = urlcon.getJarFile();) {
							final Enumeration<JarEntry> entries = jar.entries();
							while (entries.hasMoreElements()) {
								final String entry = entries.nextElement().getName();
								if (entry.startsWith(pathPrefix)) {
									if (entry.lastIndexOf("/") != pathPrefix.length() - 1) {
										LOGGER.log(Level.WARNING, "Resources in sub directory path=" + entry
												+ " are ignored for loading native libs!");
										continue;
									}
									final String toAdd = entry.substring(pathPrefix.length());
									// System.out.println(" added -> " + toAdd);
									if (toAdd.length() > 0) {
										ret.add(new URL(rootUrlStr + pathPrefix + toAdd));
									}
								}
							}
						}
					} catch (final IOException e) {
						throw new RuntimeException("ERROR: Failed to read from JAR file=" + url, e);
					}
				} else {
					throw new RuntimeException("ERROR: URL protocol=" + url.getProtocol()
							+ " not yet supported to unpack native libraries from.");
				}
			}
		} catch (final IOException e) {
			throw new RuntimeException("ERROR: Failed to get resources for " + Platform.RESOURCE_PREFIX, e);
		}
		return ret;
	}

	// Workaround - not needed yet - left commented just for knowldge
	// public static void loadDependentLibraries() {
	// final String[] dependentLibNames =
	// mapDependentLibs.get(Platform.RESOURCE_PREFIX);
	// if (dependentLibNames == null) {
	// return;
	// }
	// for (final String libName : dependentLibNames) {
	// System.loadLibrary(libName);
	// }
	// }

}
