# jna-utils
Utility package for Java JNA

## Purpose
This module provides methods to extract native dynamic library files bundled in a JAR on the classpath to any folder on the runtime filesystem.<br>
A single JAR can contain dynamic libraries for multiple platforms.<br>
This utility module will load the appropriate library based on the actual platform at runtime.

## Maven
```xml
<dependency>
  <groupId>de.mhoffrogge.jna</groupId>
  <artifactId>jna-utils</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Features
* A single JAR can contain native dynamic libraries for multiple platforms.<br>
* The target directory of the unpacked dynamic libraries will be prepended to the environment settings of *java.library.path* as well as to the OS *PATH* (Windows) or *LD_LIBRARY_PATH* (Linux) variable.
* If the native dynamic librar(y|ies) ecplicitely loaded by your application depend on other dynamic libraries not being loaded explicitley, then those dependent libraries can be bundled as well and they will be found by the OS loader due to the previously mentioned environment variable updates
* All dynamic libraries found in folder **/<com.sun.jna.Platform.RESOURCE_PREFIX>** will be extracted

## Conventions
* The bundled dynamic libraries must be located in folder **/<com.sun.jna.Platform.RESOURCE_PREFIX>** inside the JAR:<br>
```
 /
 ├──win32-x86
 │   ├──foo_jna_loaded.dll
 │   └──foo_required.dll
 │
 └──win32-x86-64
     ├──foo_jna_loaded.dll
     └──foo_required.dll
```
* The JAR to be used for extracting native dynamic libraries is determined on the classpath by passing a JARs class to the appropriate jna-utils API method.
* If there are multiple JARs on the classpath containing the same reference class, only the first JAR found on the classpath will be used for looking up the native libraries

## Limitations
* Currently only Windows and Linux platforms supported
* Linux not yet tested

## License
This project is licensed under [Apache License, Version 2.0](http://opensource.org/licenses/apache-2.0).