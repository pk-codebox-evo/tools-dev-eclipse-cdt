/*******************************************************************************
 * Copyright (c) 2015, 2016 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.cdt.qt.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.cdt.build.core.CBuildConfiguration;
import org.eclipse.cdt.build.core.IToolChain;
import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.model.ILanguage;
import org.eclipse.cdt.core.model.LanguageManager;
import org.eclipse.cdt.core.parser.IExtendedScannerInfo;
import org.eclipse.cdt.core.parser.IScannerInfo;
import org.eclipse.cdt.internal.qt.core.Activator;
import org.eclipse.core.resources.IBuildConfiguration;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

public class QtBuildConfiguration extends CBuildConfiguration {

	private static final String QTINSTALL_NAME = "cdt.qt.install.name"; //$NON-NLS-1$
	private static final String LAUNCH_MODE = "cdt.qt.launchMode"; //$NON-NLS-1$

	private final IQtInstall qtInstall;
	private final String launchMode;
	private Map<String, String> properties;

	public QtBuildConfiguration(IBuildConfiguration config) {
		super(config);

		Preferences settings = getSettings();
		String installName = settings.get(QTINSTALL_NAME, ""); //$NON-NLS-1$
		if (!installName.isEmpty()) {
			IQtInstallManager manager = Activator.getService(IQtInstallManager.class);
			qtInstall = manager.getInstall(installName);
		} else {
			qtInstall = null;
		}

		launchMode = settings.get(LAUNCH_MODE, ""); //$NON-NLS-1$
	}

	public QtBuildConfiguration(IBuildConfiguration config, IToolChain toolChain, IQtInstall qtInstall,
			String launchMode) {
		super(config, toolChain);
		this.qtInstall = qtInstall;
		this.launchMode = launchMode;

		Preferences settings = getSettings();
		settings.put(QTINSTALL_NAME, qtInstall.getName());
		settings.put(LAUNCH_MODE, launchMode);
		try {
			settings.flush();
		} catch (BackingStoreException e) {
			Activator.log(e);
		}
	}

	public IQtInstall getQtInstall() {
		return qtInstall;
	}

	public String getLaunchMode() {
		return launchMode;
	}

	public String getQmakeCommand() {
		return qtInstall.getQmakePath().toString();
	}

	public String getQmakeConfig() {
		switch (launchMode) {
		case "run": //$NON-NLS-1$
			return "CONFIG+=release"; //$NON-NLS-1$
		case "debug": //$NON-NLS-1$
			return "CONFIG+=debug"; //$NON-NLS-1$
		default:
			// TODO probably need an extension point for guidance
			return null;
		}
	}

	public Path getProjectFile() {
		File projectDir = getProject().getLocation().toFile();
		File[] proFiles = projectDir.listFiles((dir, name) -> name.endsWith(".pro")); //$NON-NLS-1$
		if (proFiles.length > 0) {
			// TODO what if there are more than one.
			return proFiles[0].toPath();
		} else {
			return null;
		}
	}

	public Path getProgramPath() throws CoreException {
		switch (Platform.getOS()) {
		case Platform.OS_MACOSX:
			// TODO this is mac local specific and really should be
			// in the config
			// TODO also need to pull the app name out of the pro
			// file name
			Path appFolder = getBuildDirectory().resolve("main.app");
			Path contentsFolder = appFolder.resolve("Contents");
			Path macosFolder = contentsFolder.resolve("MacOS");
			return macosFolder.resolve("main");
		case Platform.OS_WIN32:
			Path releaseFolder = getBuildDirectory().resolve("release");
			return releaseFolder.resolve("main.exe");
		default:
			throw new CoreException(
					new Status(IStatus.ERROR, Activator.ID, "platform not supported: " + Platform.getOS()));
		}
	}

	public void setProgramEnvironment(Map<String, String> env) {
		Path libPath = getQtInstall().getLibPath();
		switch (Platform.getOS()) {
		case Platform.OS_MACOSX:
			String libPathEnv = env.get("DYLD_LIBRARY_PATH");
			if (libPathEnv == null) {
				libPathEnv = libPath.toString();
			} else {
				libPathEnv = libPath.toString() + File.pathSeparator + libPathEnv;
			}
			env.put("DYLD_LIBRARY_PATH", libPathEnv);
			break;
		case Platform.OS_WIN32:
			String path = env.get("PATH");
			// TODO really need a bin path
			// and resolve doesn't work properly on Windows
			path = "C:/Qt/5.5/mingw492_32/bin;" + path;
			env.put("PATH", path);
			break;
		}
	}

	public String getProperty(String key) {
		if (properties == null) {
			List<String> cmd = new ArrayList<>();
			cmd.add(getQmakeCommand());
			cmd.add("-E"); //$NON-NLS-1$

			String config = getQmakeConfig();
			if (config != null) {
				cmd.add(config);
			}

			cmd.add(getProjectFile().toString());

			try {
				ProcessBuilder procBuilder = new ProcessBuilder(cmd).directory(getProjectFile().getParent().toFile());
				getToolChain().setEnvironment(procBuilder.environment());
				Process proc = procBuilder.start();
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
					properties = new HashMap<>();
					for (String line = reader.readLine(); line != null; line = reader.readLine()) {
						int i = line.indexOf('=');
						if (i >= 0) {
							String k = line.substring(0, i);
							String v = line.substring(i + 1);
							properties.put(k.trim(), v.trim());
						}
					}
				}
			} catch (IOException e) {
				Activator.log(e);
			}
		}

		return properties != null ? properties.get(key) : null;
	}

	@Override
	public IScannerInfo getScannerInfo(IResource resource) throws IOException {
		IScannerInfo info = getCachedScannerInfo(resource);
		if (info == null) {
			String cxx = getProperty("QMAKE_CXX"); //$NON-NLS-1$
			if (cxx == null) {
				Activator.log("No QMAKE_CXX for " + qtInstall.getSpec()); //$NON-NLS-1$
				return null;
			}
			String[] cxxSplit = cxx.split(" "); //$NON-NLS-1$
			String command = cxxSplit[0];

			List<String> args = new ArrayList<>();
			for (int i = 1; i < cxxSplit.length; ++i) {
				args.add(cxxSplit[i]);
			}
			args.addAll(Arrays.asList(getProperty("QMAKE_CXXFLAGS").split(" "))); //$NON-NLS-1$ //$NON-NLS-2$
			args.add("-o"); //$NON-NLS-1$
			args.add("-"); //$NON-NLS-1$

			String srcFile;
			if (resource instanceof IFile) {
				srcFile = resource.getLocation().toOSString();
				// Only add file if it's an IFile
				args.add(srcFile);
			} else {
				// Doesn't matter, the toolchain will create a tmp file for this
				srcFile = "scannerInfo.cpp"; //$NON-NLS-1$
			}

			String[] includePaths = getProperty("INCLUDEPATH").split(" "); //$NON-NLS-1$ //$NON-NLS-2$
			for (int i = 0; i < includePaths.length; ++i) {
				Path path = Paths.get(includePaths[i]);
				if (!path.isAbsolute()) {
					includePaths[i] = getBuildDirectory().resolve(path).toString();
				}
			}

			ILanguage language = LanguageManager.getInstance()
					.getLanguage(CCorePlugin.getContentType(getProject(), srcFile), getProject()); // $NON-NLS-1$
			Path dir = Paths.get(getProject().getLocationURI());
			IExtendedScannerInfo extendedInfo = getToolChain().getScannerInfo(command, args,
					Arrays.asList(includePaths), resource, dir);
			putScannerInfo(language, extendedInfo);
			info = extendedInfo;
		}
		return info;
	}

	@Override
	public void clearScannerInfoCache() throws CoreException {
		super.clearScannerInfoCache();
		properties = null;
	}

}
