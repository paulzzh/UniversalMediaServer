/*
 * This file is part of Universal Media Server, based on PS3 Media Server.
 *
 * This program is a free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; version 2 of the License only.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package net.pms.library.container;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import net.pms.library.LibraryContainer;
import net.pms.library.item.SevenZipEntry;
import net.pms.renderers.Renderer;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;
import net.sf.sevenzipjbinding.simple.ISimpleInArchive;
import net.sf.sevenzipjbinding.simple.ISimpleInArchiveItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SevenZipFile extends LibraryContainer {
	private static final Logger LOGGER = LoggerFactory.getLogger(SevenZipFile.class);
	private File file;
	private IInArchive arc;

	public SevenZipFile(Renderer renderer, File f) {
		super(renderer, f.getName(), null);
		file = f;
		setLastModified(file.lastModified());
		try {
			RandomAccessFile rf = new RandomAccessFile(f, "r");
			arc = SevenZip.openInArchive(null, new RandomAccessFileInStream(rf));
			ISimpleInArchive simpleInArchive = arc.getSimpleInterface();

			for (ISimpleInArchiveItem item : simpleInArchive.getArchiveItems()) {
				LOGGER.debug("found " + item.getPath() + " in arc " + file.getAbsolutePath());

				// Skip folders for now
				if (item.isFolder()) {
					continue;
				}
				addChild(new SevenZipEntry(renderer, f, item.getPath(), item.getSize()));
			}
		} catch (IOException e) {
			LOGGER.error("Error reading archive file", e);
		} catch (NullPointerException e) {
			LOGGER.error("Caught 7-Zip Null-Pointer Exception", e);
		}
	}

	@Override
	public String getName() {
		return file.getName();
	}

	@Override
	public String getSystemName() {
		return file.getAbsolutePath();
	}

	@Override
	public boolean isValid() {
		return file.exists();
	}
}