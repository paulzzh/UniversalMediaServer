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
package net.pms.store.container;

import java.io.IOException;
import net.pms.database.MediaTableCoverArtArchive;
import net.pms.dlna.DLNAThumbnailInputStream;
import net.pms.media.MediaInfo;
import net.pms.media.audio.metadata.MediaAudioMetadata;
import net.pms.media.audio.metadata.MusicBrainzAlbum;
import net.pms.renderers.Renderer;
import net.pms.store.DbIdMediaType;
import net.pms.store.DbIdTypeAndIdent;

public class MusicBrainzAlbumFolder extends VirtualFolderDbIdNamed {

	public MusicBrainzAlbumFolder(Renderer renderer, String folderName, DbIdTypeAndIdent typeIdent) {
		super(renderer, folderName, typeIdent);
	}

	public MusicBrainzAlbumFolder(Renderer renderer, MusicBrainzAlbum album) {
		this(renderer, album.getAlbum(), album.getMbReleaseid(), album.getAlbum(), album.getArtist(), album.getYear(), album.getGenre());
	}

	public MusicBrainzAlbumFolder(Renderer renderer, String folderName, String mbReleaseid, String album, String artist, Integer year, String genre) {
		super(renderer, folderName, new DbIdTypeAndIdent(DbIdMediaType.TYPE_MUSICBRAINZ_RECORDID, mbReleaseid));
		MediaInfo fakeMediaInfo = new MediaInfo();
		MediaAudioMetadata fakeAudioMetadata = new MediaAudioMetadata();
		fakeAudioMetadata.setAlbum(album);
		fakeAudioMetadata.setArtist(artist);
		fakeAudioMetadata.setYear(year);
		fakeAudioMetadata.setGenre(genre);
		fakeMediaInfo.setAudioMetadata(fakeAudioMetadata);
		setMediaInfo(fakeMediaInfo);
	}

	@Override
	public DLNAThumbnailInputStream getThumbnailInputStream() throws IOException {
		MediaTableCoverArtArchive.CoverArtArchiveResult res = MediaTableCoverArtArchive.findMBID(getMediaIdent());
		if (res.isFound()) {
			return DLNAThumbnailInputStream.toThumbnailInputStream(res.getCoverBytes());
		}
		return super.getThumbnailInputStream();
	}

}