package com.hjh.files.sync.client;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.apache.http.util.Asserts;

import com.hjh.files.sync.common.HLogFactory;
import com.hjh.files.sync.common.ILog;
import com.hjh.files.sync.common.RemoteFile;
import com.hjh.files.sync.common.RemoteFileFactory;
import com.hjh.files.sync.common.RemoteFileManage;
import com.hjh.files.sync.common.util.MD5;

public class ClientFolder {

	private static ILog logger = HLogFactory.create(ClientFolder.class);
	private final static String CLIENT_CACHE_FOLDER_NAME = ".c.cache";

	private RemoteFileManage fromManage;

	private String store_folder;
	private String name;
	private String url;
	private File cache;

	public ClientFolder(String name, String store_folder, String url) {
		this.name = name;
		this.store_folder = store_folder;
		this.url = url;
		this.cache = new File(store_folder, CLIENT_CACHE_FOLDER_NAME);
		if (!this.cache.isDirectory()) {
			Asserts.check(this.cache.mkdir(),
					"can not create cache folder for client on :" + this.cache.getAbsolutePath());
		}
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getStore_folder() {
		return store_folder;
	}

	public void setStore_folder(String store_folder) {
		this.store_folder = store_folder;
	}

	public void sync() throws IOException {

		String store_path = new File(new File(store_folder), name).getCanonicalPath();
		if (null == fromManage) {
			fromManage = RemoteFileFactory.queryManage(url);
		}
		logger.info(String.format("sync[%s] %s => %s", name, url, store_path));
		File root = new File(store_path);
		if (!root.exists()) {
			root.mkdir();
		}
		Asserts.check(root.isDirectory(), "must be a directory :" + store_path);

		doSync(null, root);
	}

	private void doSync(RemoteFile from, File target) throws IOException {

		if (null == from || from.isFolder()) { // 目录同步
			RemoteFile[] remotes = fromManage.list(from);
			if (target.isFile()) {
				logger.info("remove file:" + target.getAbsolutePath());
				Asserts.check(target.delete(), "delete file fail : " + target.getAbsolutePath());
			}
			if (!target.exists()) {
				logger.info(String.format("sync folder[%s] %s => %s", name, from.path(), target.getAbsolutePath()));
				Asserts.check(target.mkdir(), "create folder fail : " + target.getAbsolutePath());
			}
			String[] exists = target.list();
			for (RemoteFile item : remotes) {
				doSync(item, new File(target, item.name()));
				if (null != exists) {
					for (int i = 0; i < exists.length; i++) {
						if (exists[i] != null && exists[i].equals(item.name())) {
							exists[i] = null;
							break;
						}
					}
				}
			}
			if (null != exists) {
				for (int i = 0; i < exists.length; i++) {
					if (exists[i] != null) {
						File cur_exist = new File(target, exists[i]);
						if (cur_exist.isDirectory()) {
							logger.info("remove directory:" + cur_exist.getAbsolutePath());
							FileUtils.deleteDirectory(cur_exist);
						} else {
							logger.info("remove file:" + cur_exist.getAbsolutePath());
							Asserts.check(cur_exist.delete(), "can not delete file :" + cur_exist.getAbsolutePath());
						}
					}
				}
			}
		} else { // 文件同步
			if (!isSame(from, target)) {
				logger.info(String.format("sync file[%s] %s => %s", name, from.path(), target.getAbsolutePath()));
				String md5 = fromManage.md5(from);

				if (target.isDirectory()) {
					logger.info("remove directory:" + target.getAbsolutePath());
					FileUtils.deleteDirectory(target);
				}

				String local_md5 = target.isFile() ? MD5.md5(target) : null;
				if (!md5.equals(local_md5)) {
					File current_cache_root = new File(cache, md5);
					if (!current_cache_root.exists()) {
						current_cache_root.mkdir();
					}
					int totalParts = fromManage.partCount(from);
					for (int i = 0; i < totalParts; i++) {
						File cur_part = new File(current_cache_root, i + "");
						if (!cur_part.exists()) {
							FileUtils.writeByteArrayToFile(cur_part, fromManage.part(from, i));
						}
					}

					File remoteFileData = new File(current_cache_root, "current");
					if (remoteFileData.isDirectory()) {
						logger.info("remove directory:" + remoteFileData.getAbsolutePath());
						FileUtils.deleteDirectory(remoteFileData);
					}

					if (remoteFileData.isFile()) {
						String cache_md5 = MD5.md5(remoteFileData);
						if (!md5.equals(cache_md5)) {
							Asserts.check(remoteFileData.delete(),
									"can not delete error file[md5 do not match]:" + remoteFileData.getAbsolutePath());
						}
					}

					if (!remoteFileData.exists()) {
						for (int i = 0; i < totalParts; i++) {
							File cur_part = new File(current_cache_root, i + "");
							FileUtils.writeByteArrayToFile(remoteFileData, FileUtils.readFileToByteArray(cur_part),
									true);
						}
					}

					{
						String cache_md5 = MD5.md5(remoteFileData);
						if (!md5.equals(cache_md5)) {
							logger.info("clear dirty directory : " + current_cache_root.getAbsolutePath());
							FileUtils.deleteDirectory(current_cache_root);
							throw new RuntimeException("can not fetch correct data from remote for:" + from.path());
						}
					}

					FileUtils.copyFile(remoteFileData, target);
				}
				target.setLastModified(from.lastModify());
			}
		}
	}

	private boolean isSame(RemoteFile from, File root) {
		if (from.isFolder()) {
			if (!root.isDirectory()) {
				return false;
			}
		} else {
			if (root.isDirectory()) {
				return false;
			}
		}

		if (!from.name().equals(root.getName())) {
			return false;
		}

		if (from.lastModify() != root.lastModified()) {
			return false;
		}

		if (from.length() != root.length()) {
			return false;
		}
		return true;
	}

}
