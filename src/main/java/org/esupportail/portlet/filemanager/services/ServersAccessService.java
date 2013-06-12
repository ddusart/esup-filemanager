/**
 * Copyright (C) 2012 Esup Portail http://www.esup-portail.org
 * Copyright (C) 2012 UNR RUNN http://www.unr-runn.fr
 * Copyright (C) 2012 RECIA http://www.recia.fr
 * @Author (C) 2012 Vincent Bonamy <Vincent.Bonamy@univ-rouen.fr>
 * @Contributor (C) 2012 Jean-Pierre Tran <Jean-Pierre.Tran@univ-rouen.fr>
 * @Contributor (C) 2012 Julien Marchal <Julien.Marchal@univ-nancy2.fr>
 * @Contributor (C) 2012 Julien Gribonvald <Julien.Gribonvald@recia.fr>
 * @Contributor (C) 2012 David Clarke <david.clarke@anu.edu.au>
 * @Contributor (C) 2012 BULL http://www.bull.fr
 * @Contributor (C) 2012 Pierre Bouvret <pierre.bouvret@u-bordeaux4.fr>
 * @Contributor (C) 2012 Franck Bordinat <franck.bordinat@univ-jfc.fr>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.esupportail.portlet.filemanager.services;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.annotation.Resource;
import javax.portlet.PortletRequest;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.esupportail.portlet.filemanager.beans.DownloadFile;
import org.esupportail.portlet.filemanager.beans.DrivesCategory;
import org.esupportail.portlet.filemanager.beans.JsTreeFile;
import org.esupportail.portlet.filemanager.beans.SharedUserPortletParameters;
import org.esupportail.portlet.filemanager.beans.UserPassword;
import org.esupportail.portlet.filemanager.crudlog.CrudLogLevel;
import org.esupportail.portlet.filemanager.crudlog.CrudLoggable;
import org.esupportail.portlet.filemanager.exceptions.EsupStockLostSessionException;
import org.esupportail.portlet.filemanager.utils.PathEncodingUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;


@Service("serversAccess")
@Scope(value="session", proxyMode=ScopedProxyMode.INTERFACES)
public class ServersAccessService implements DisposableBean, IServersAccessService {

	protected static final Log log = LogFactory.getLog(ServersAccessService.class);

	protected Map<String, FsAccess> servers = new HashMap<String, FsAccess>();

	protected Map<String, Map<String, FsAccess>> restrictedServers = new HashMap<String,  Map<String, FsAccess>>();

	protected Map<String, Boolean> isInitializedMap = new HashMap<String, Boolean>();

	@Autowired
	public void setServers(List<FsAccess> servers) {
		for(FsAccess server: servers) {
			this.servers.put(server.getDriveName(), server);
		}
	}

	@Resource(name="drivesCategories")
	protected Map<String, DrivesCategory> drivesCategories;

	@Autowired
	protected PathEncodingUtils pathEncodingUtils;

	public List<String> getRestrictedDrivesGroupsContext(PortletRequest request,
			String contextToken, Map userInfos) {

		List<String> driveNames = new ArrayList<String>(this.servers.keySet());

		// contextToken restriction
		if(contextToken != null) {
			for(FsAccess server: this.servers.values()) {
				if(server.getContextToken() == null || !contextToken.equals(server.getContextToken())) {
					driveNames.remove(server.getDriveName());
				}
			}
		}

		// groups restriction
		if(request != null)
			for(FsAccess server: this.servers.values()) {
				boolean isUserInAnyRole = false;
				if(server.getMemberOfAny() != null) {
					for(String group: server.getMemberOfAny()) {
						if(request.isUserInRole(group)) {
							isUserInAnyRole = true;
							break;
						}
					}
				} else {
					// if null -> no restriction
					isUserInAnyRole = true;
				}
				if(!isUserInAnyRole)
					driveNames.remove(server.getDriveName());
		}

		// HasAttribut restriction
		if(userInfos != null)
			for(FsAccess server: this.servers.values()) {
				boolean userHasAttributs = true;
				if(server.getHasAttributs() != null) {
					for(String attr: server.getHasAttributs().keySet()) {
						String attrValue = (String)userInfos.get(attr);
						String regex = server.getHasAttributs().get(attr);
						if(attrValue == null || !attrValue.matches(regex)) {
							userHasAttributs = false;
							break;
						}
					}
				}

				if(!userHasAttributs)
					driveNames.remove(server.getDriveName());
			}

		return driveNames;
	}


	public void initializeServices(SharedUserPortletParameters userParameters) {

		this.restrictedServers.put(userParameters.getSharedSessionId(), new HashMap<String, FsAccess>());
		Map<String, FsAccess> rServers = this.restrictedServers.get(userParameters.getSharedSessionId());

		if(userParameters.getDriveNames() != null) {
			for(String driveName : userParameters.getDriveNames()) {
				rServers.put(driveName, this.servers.get(driveName));
			}
		}
		isInitializedMap.put(userParameters.getSharedSessionId(), true);
	}

	public boolean isInitialized(SharedUserPortletParameters userParameters) {
		if(isInitializedMap.containsKey(userParameters.getSharedSessionId()))
			return isInitializedMap.get(userParameters.getSharedSessionId());
		return false;
	}

	public void destroy() throws Exception {
		for(Map<String, FsAccess> rServers : this.restrictedServers.values()) {
			for(FsAccess server: rServers.values()) {
				server.close();
			}
		}
	}

	public void updateUserParameters(String dir,
			SharedUserPortletParameters userParameters) {
		String driveName = getDrive(dir);
		FsAccess fsAccess = getFsAccess(driveName, userParameters);
		if(userParameters != null && !fsAccess.isOpened() && fsAccess.formAuthenticationRequired(userParameters)) {
			UserPassword userPassword = userParameters.getUserPassword4AuthenticatedFormDrives().get(driveName);
			if(userPassword != null)
				fsAccess.authenticate(userPassword.getUsername(), userPassword.getPassword(), userParameters);
			else {
				//log.warn("here we should have username & password ? What's wrong ? :(");
				throw new EsupStockLostSessionException("Here we should have username & password. Session lost ?");
			}
		}
	}

	public FsAccess getFsAccess(String driveName, SharedUserPortletParameters userParameters) {
		if(this.restrictedServers.get(userParameters.getSharedSessionId()).containsKey(driveName)) {
			return this.restrictedServers.get(userParameters.getSharedSessionId()).get(driveName);
		}
		else {
			log.error("pb : restrictedServers does not contain this required drive ?? : " + driveName);
			return null;
		}
	}


	protected List<FsAccess> getCategoryFsAccess(DrivesCategory dCategory, SharedUserPortletParameters userParameters) {
		List<FsAccess> drives = new ArrayList<FsAccess>();
		for(String driveName: dCategory.getDrives())
			if(this.restrictedServers.get(userParameters.getSharedSessionId()).containsKey(driveName))
				drives.add(this.restrictedServers.get(userParameters.getSharedSessionId()).get(driveName));
		return drives;
	}

	@CrudLoggable(CrudLogLevel.DEBUG)
	public JsTreeFile get(String dir, SharedUserPortletParameters userParameters, boolean folderDetails, boolean fileDetails) {
		String category = getDriveCategory(dir);
		String driveName = getDrive(dir);
		if(category == null || category.length() == 0) {
			return getJsTreeFileRoot();
		} else if(driveName == null || driveName.length() == 0) {
			// get category
			DrivesCategory dCat = this.drivesCategories.get(category);
			JsTreeFile jsTreeFile = new JsTreeFile(category, "", "category");
			jsTreeFile.setIcon(dCat.getIcon());
			jsTreeFile.setCategory(category, dCat.getIcon());
			return jsTreeFile;
		} else {
			// get drive or folder or file
			String path = getLocalDir(dir);
			log.debug("UVHC::ServerAccesService::get::path::"+path);
			JsTreeFile jsTreeFile = this.getFsAccess(driveName, userParameters).get(path, userParameters, folderDetails, fileDetails);
			DrivesCategory dCat = this.drivesCategories.get(category);
			jsTreeFile.setCategory(category, dCat.getIcon());
			jsTreeFile.setDrive(driveName, this.getFsAccess(driveName, userParameters).getIcon());
			if(jsTreeFile.getTitle().length() == 0) {
				// this the folder root == the drive
				jsTreeFile.setTitle(driveName);
				jsTreeFile.setIcon(this.getFsAccess(driveName, userParameters).getIcon());
			}
			return jsTreeFile;
		}
	}

	@CrudLoggable(CrudLogLevel.DEBUG)
	public List<JsTreeFile> getChildren(String dir, SharedUserPortletParameters userParameters) {
		String category = getDriveCategory(dir);
		String driveName = getDrive(dir);
		DrivesCategory dCat = this.drivesCategories.get(category);
		if(category == null || category.length() == 0) {
			return getJsTreeFileRoots(userParameters).get(0).getChildren();
		} else if(driveName == null || driveName.length() == 0) {
			// getChildren on a category -> list drives
			List<JsTreeFile> files = new ArrayList<JsTreeFile>();
			for(FsAccess drive: getCategoryFsAccess(dCat, userParameters)) {
				JsTreeFile jsTreeFile = new JsTreeFile(drive.getDriveName(), "", "drive");
				jsTreeFile.setIcon(drive.getIcon());
				jsTreeFile.setCategory(category, dCat.getIcon());
				jsTreeFile.setDrive(drive.getDriveName(), drive.getIcon());
				files.add(jsTreeFile);
			}
			return files;
		} else {
			// getChildren on a folder (or drive) -> get children on a fsAccess
			String path = getLocalDir(dir);
			List<JsTreeFile> files = this.getFsAccess(driveName, userParameters).getChildren(path, userParameters);
			for(JsTreeFile file: files) {
				file.setCategory(category, dCat.getIcon());
				file.setDrive(driveName, this.getFsAccess(driveName, userParameters).getIcon());
			}
			return files;
		}
	}

	@CrudLoggable(CrudLogLevel.DEBUG)
	public List<JsTreeFile> getFolderChildren(String dir,
			SharedUserPortletParameters userParameters) {
		List<JsTreeFile> files = this.getChildren(dir, userParameters);
		List<JsTreeFile> folders = new ArrayList<JsTreeFile>();
		for(JsTreeFile file: files) {
			if(!"file".equals(file.getType()))
				folders.add(file);
		}
		Collections.sort(folders);
		return folders;
	}

	@CrudLoggable(CrudLogLevel.INFO)
	public boolean remove(String dir, SharedUserPortletParameters userParameters) {
		return this.getFsAccess(getDrive(dir), userParameters).remove(getLocalDir(dir), userParameters);
	}

	@CrudLoggable(CrudLogLevel.INFO)
	public String createFile(String parentDir, String title, String type, SharedUserPortletParameters userParameters) {
		String drive = getDrive(parentDir);
		if(drive == null) {
			log.error("Can't create file/folder because we can't retrieve associated drive on this dir : " + parentDir);
			return null;
		}
		return this.getFsAccess(drive, userParameters).createFile(getLocalDir(parentDir), title, type, userParameters);
	}

	@CrudLoggable(CrudLogLevel.INFO)
	public boolean renameFile(String dir, String title, SharedUserPortletParameters userParameters) {
		String drive = getDrive(dir);
		if(drive == null) {
			log.error("Can't rename file/folder because we can't retrieve associated drive on this dir : " + dir);
			return false;
		}
		return this.getFsAccess(drive, userParameters).renameFile(getLocalDir(dir), title, userParameters);
	}

	private boolean interMoveCopyFile(String newDir, String refDir, boolean copy, SharedUserPortletParameters userParameters) {
		JsTreeFile ref = this.get(refDir, userParameters, false, false);
		boolean allIsOk = true;
		if("file".equals(ref.getType())) {
			DownloadFile file = this.getFile(refDir, userParameters);
			allIsOk = this.putFile(newDir, file.getBaseName(), file.getInputStream(), userParameters);
		} else {
			String localDirParent = this.createFile(newDir, ref.getTitle(), ref.getType(), userParameters);
			String dirParent = JsTreeFile.ROOT_DRIVE.concat(getDriveCategory(newDir)).concat(JsTreeFile.DRIVE_PATH_SEPARATOR).concat(getDrive(newDir)).concat(JsTreeFile.DRIVE_PATH_SEPARATOR).concat(localDirParent);
			for(JsTreeFile child: this.getChildren(refDir, userParameters)) {
				allIsOk = allIsOk && this.interMoveCopyFile(dirParent, child.getPath(), copy, userParameters);
			}
		}
		if(allIsOk && !copy) {
			allIsOk = this.remove(refDir, userParameters);
		}
		return allIsOk;
	}

	@CrudLoggable(CrudLogLevel.INFO)
	public boolean moveCopyFilesIntoDirectory(String dir, List<String> filesToCopy, boolean copy, SharedUserPortletParameters userParameters) {
		String driveName = getDrive(dir);
		if(driveName.equals(getDrive(filesToCopy.get(0))) &&
				( (copy && this.getFsAccess(driveName, userParameters).supportIntraCopyPast()) || (!copy && this.getFsAccess(driveName, userParameters).supportIntraCutPast())) ) {
			return this.getFsAccess(driveName, userParameters).moveCopyFilesIntoDirectory(getLocalDir(dir), getLocalDirs(filesToCopy), copy, userParameters);
		} else {
			boolean allIsOk = true;
			for(String fileToCopy: filesToCopy) {
				boolean isOk = this.interMoveCopyFile(dir, fileToCopy, copy, userParameters);
				if(isOk && !copy)
					this.remove(fileToCopy, userParameters);
				allIsOk = allIsOk && isOk;
			}
			return allIsOk;
		}
	}

	@CrudLoggable(CrudLogLevel.DEBUG)
	public DownloadFile getFile(String dir, SharedUserPortletParameters userParameters) {
		return this.getFsAccess(getDrive(dir), userParameters).getFile(getLocalDir(dir), userParameters);
	}

	@CrudLoggable(CrudLogLevel.INFO)
	public boolean  putFile(String dir, String filename, InputStream inputStream, SharedUserPortletParameters userParameters) {
		return this.getFsAccess(getDrive(dir), userParameters).putFile(getLocalDir(dir), filename, inputStream, userParameters);
	}

	public JsTreeFile getJsTreeFileRoot() {
		JsTreeFile jsFileRoot = new JsTreeFile(JsTreeFile.ROOT_DRIVE_NAME, null, "root");
		jsFileRoot.setIcon(JsTreeFile.ROOT_ICON_PATH);
		return jsFileRoot;
	}

	public List<JsTreeFile> getJsTreeFileRoots(SharedUserPortletParameters userParameters) {
		JsTreeFile jsFileRoot = getJsTreeFileRoot();
		List<JsTreeFile> jsTreeFiles = new ArrayList<JsTreeFile>();
		for(String drivesCategoryName: this.drivesCategories.keySet()) {
			DrivesCategory category = this.drivesCategories.get(drivesCategoryName);
			if(!getCategoryFsAccess(category, userParameters).isEmpty()) {
				JsTreeFile jFile = new JsTreeFile(drivesCategoryName, "", "category");
				jFile.setIcon(this.drivesCategories.get(drivesCategoryName).getIcon());
				jFile.setCategory(drivesCategoryName, this.drivesCategories.get(drivesCategoryName).getIcon());
				jFile.setChildren(this.getChildren(jFile.getPath(), userParameters));
				jsTreeFiles.add(jFile);
			}
		}
		jsFileRoot.setChildren(jsTreeFiles);
		List<JsTreeFile> jsTreeFileRoots = new ArrayList<JsTreeFile>();
		jsTreeFileRoots.add(jsFileRoot);
		return jsTreeFileRoots;
	}


	public List<JsTreeFile> getJsTreeFileRoots(String dir, SharedUserPortletParameters userParameters) {

		JsTreeFile parentFile = null;

		List<JsTreeFile> rootAndDrivesAndCategories = this.getJsTreeFileRoots(userParameters);
		JsTreeFile jFile = this.get(dir, userParameters, false, false);

		//Iterator<String> parentsPathes = jFile.getParentsPathes().keySet().iterator();
		Iterator<String> parentsPathes = pathEncodingUtils.getParentsPathes(jFile.getPath(), null, null).keySet().iterator();
		String parentPath = parentsPathes.next();
		Assert.isTrue(JsTreeFile.ROOT_DRIVE.equals(parentPath));

		if(!parentsPathes.hasNext())
			return rootAndDrivesAndCategories;

		parentPath = parentsPathes.next();
		for(JsTreeFile drive: rootAndDrivesAndCategories.get(0).getChildren()) {
			if(drive.getPath().equals(parentPath)) {
				parentFile = drive;
				break;
			}
		}

		if(!parentsPathes.hasNext())
			return rootAndDrivesAndCategories;

		parentPath = parentsPathes.next();
		for(JsTreeFile category: parentFile.getChildren()) {
			if(category.getPath().equals(parentPath)) {
				parentFile = category;
				break;
			}
		}

		while(parentPath != null) {
			List<JsTreeFile> folders = this.getFolderChildren(parentFile.getPath(), userParameters);
			parentFile.setChildren(folders);

			if(!parentsPathes.hasNext()) {
				parentPath = null;
			} else {
				parentPath = parentsPathes.next();
				for(JsTreeFile child: folders) {
					if(child.getPath().equals(parentPath)) {
						parentFile = child;
						break;
					}
				}
			}
		}
		return rootAndDrivesAndCategories;
	}

	public String getDriveCategory(String dir) {
		if(dir == null || dir.length() <= JsTreeFile.ROOT_DRIVE.length())
			return null;
		dir = dir.substring(JsTreeFile.ROOT_DRIVE.length());
		String[] driveAndDir = dir.split(JsTreeFile.DRIVE_PATH_SEPARATOR, 3);
		return driveAndDir[0];
	}

	public String getDrive(String dir) {
		String drive = null;
		if(dir != null && dir.length() > JsTreeFile.ROOT_DRIVE.length()) {
			dir = dir.substring(JsTreeFile.ROOT_DRIVE.length());
			String[] driveAndDir = dir.split(JsTreeFile.DRIVE_PATH_SEPARATOR, 3);
			if(driveAndDir.length > 1)
				drive = driveAndDir[1];
		}
		return drive;
	}

	private String getLocalDir(String dir) {
		dir = dir.substring(JsTreeFile.ROOT_DRIVE.length());
		String[] driveAndDir = dir.split(JsTreeFile.DRIVE_PATH_SEPARATOR, 3);
		if(driveAndDir.length > 2)
			return driveAndDir[2];
		else
			return "";
	}

	private List<String> getLocalDirs(List<String> dirs) {
		List<String> localDirs = new ArrayList<String>();
		for(String dir: dirs)
			localDirs.add(getLocalDir(dir));
		return localDirs;
	}

	@CrudLoggable(CrudLogLevel.DEBUG)
	public DownloadFile getZip(List<String> dirs, SharedUserPortletParameters userParameters) throws IOException {	
		File tmpFile = File.createTempFile("esup-stock-zip.", ".tmp");
		// we call tmpFile.deleteOnExit so that ths tmp file will be deleted when jvm stops ...
		// see also DownloadFile.finalize
		tmpFile.deleteOnExit();
		
		FileOutputStream output = new FileOutputStream(tmpFile);
		ZipOutputStream out = new ZipOutputStream(output);
		for(String dir: dirs) {
			this.addChildrensTozip(out, dir, "", userParameters);
		}
		out.close();
		output.close();

		String contentType = "application/zip";
		int size = (int)tmpFile.length();
		String baseName = "export.zip";
		InputStream inputStream =  new FileInputStream(tmpFile);
		output.close();

		return new DownloadFile(contentType, size, baseName, inputStream, tmpFile);
	}

	private static String unAccent(String s) {
		String temp = Normalizer.normalize(s, Normalizer.Form.NFD);
	    Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
	    return pattern.matcher(temp).replaceAll("");
	}

	private void addChildrensTozip(ZipOutputStream out, String dir, String folder, SharedUserPortletParameters userParameters) throws IOException {
		JsTreeFile tFile = get(dir, userParameters, false, false);
		if("file".equals(tFile.getType())) {
			DownloadFile dFile = getFile(dir, userParameters);

			//GIP Recia : In some cases (ie, file has NTFS security permissions set), the dFile may be Null.
			//So we must check for null in order to prevent a general catastrophe
			if (dFile == null) {
				log.warn("Download file is null!  " + dir);
				return;
			}
			String fileName =  unAccent(folder.concat(dFile.getBaseName()));

			//With java 7, encoding should be added to support special characters in the file names
			//http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4244499
			out.putNextEntry(new ZipEntry(fileName));
			out.write(IOUtils.toByteArray(dFile.getInputStream()));
			out.closeEntry();
		} else {
			folder = unAccent(folder.concat(tFile.getTitle()).concat("/"));
			//Added for GIP Recia : This creates an empty file with the same name as the directory but it allows
			//for zipping empty directories
			out.putNextEntry(new ZipEntry(folder));
			out.closeEntry();
			List<JsTreeFile> childrens = this.getChildren(dir, userParameters);
			for(JsTreeFile child: childrens) {
				this.addChildrensTozip(out, child.getPath(), folder, userParameters);
			}
		}
	}


	public boolean formAuthenticationRequired(String dir, SharedUserPortletParameters userParameters) {
		if(getDrive(dir) == null)
			return false;
		return this.getFsAccess(getDrive(dir), userParameters).formAuthenticationRequired(userParameters);
	}


	public UserPassword getUserPassword(String dir, SharedUserPortletParameters userParameters) {
		if(getDrive(dir) == null)
			return null;
		return this.getFsAccess(getDrive(dir), userParameters).getUserPassword(userParameters);
	}

	public boolean authenticate(String dir, String username, String password, SharedUserPortletParameters userParameters) {

		boolean authenticateSuccess = this.getFsAccess(getDrive(dir), userParameters).authenticate(username, password, userParameters);

		if(authenticateSuccess) {
			// we keep username+password in session so that we can reauthenticate on drive in servlet mode
			// (and so that download file would be ok with the servlet ...)
			String driveName = this.getDrive(dir);
			userParameters.getUserPassword4AuthenticatedFormDrives().put(driveName, new UserPassword(username, password));
		}

		return authenticateSuccess;
	}


	public String getFirstAvailablePath(SharedUserPortletParameters userParameters, String[] prefsDefaultPathes) {
		String defaultPath = JsTreeFile.ROOT_DRIVE;
		Map<String, FsAccess> rServers = this.restrictedServers.get(userParameters.getSharedSessionId());
		for(String prefDefaultPath: prefsDefaultPathes) {
			String drive = getDrive(prefDefaultPath);
			if(rServers.get(drive) != null) {
				defaultPath = prefDefaultPath;
				break;
			}
		}
		return defaultPath;
	}

}
