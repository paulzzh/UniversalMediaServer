package net.pms.store.container;

import java.io.IOException;
import net.pms.database.MediaTableCoverArtArchive;
import net.pms.dlna.DLNAThumbnailInputStream;
import net.pms.renderers.Renderer;
import net.pms.store.DbIdMediaType;
import net.pms.store.DbIdTypeAndIdent;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MusicBrainzPersonFolder extends VirtualFolderDbIdNamed {

	private static final Logger LOGGER = LoggerFactory.getLogger(MusicBrainzPersonFolder.class);

	private VirtualFolderDbId allFiles = null;
	private VirtualFolderDbId albumFiles = null;

	public MusicBrainzPersonFolder(Renderer renderer, String personName, DbIdTypeAndIdent typeIdent) {
		super(renderer, personName, typeIdent);
		if (StringUtils.isAllBlank(typeIdent.ident)) {
			LOGGER.debug("person name is blanc");
		} else {
			initChilds();
		}
	}

	@Override
	public void discoverChildren() {
		getChildren().clear();
		initChilds();
	}

	private void initChilds() {
		DbIdTypeAndIdent tiAllFilesFolder = new DbIdTypeAndIdent(DbIdMediaType.TYPE_PERSON_ALL_FILES, getMediaIdent());
		DbIdTypeAndIdent tiAlbumFolder = new DbIdTypeAndIdent(DbIdMediaType.TYPE_PERSON_ALBUM, getMediaIdent());
		allFiles = new VirtualFolderDbId(renderer, "AllAudioTracks", tiAllFilesFolder);
		albumFiles = new VirtualFolderDbId(renderer, "ByAlbum_lowercase", tiAlbumFolder);
		addChild(allFiles);
		addChild(albumFiles);
	}

	@Override
	public DLNAThumbnailInputStream getThumbnailInputStream() throws IOException {
		MediaTableCoverArtArchive.CoverArtArchiveResult res = MediaTableCoverArtArchive.findMBID(getMediaIdent());
		if (res.isFound()) {
			return DLNAThumbnailInputStream.toThumbnailInputStream(res.getCoverBytes());
		}
		return super.getThumbnailInputStream();
	}

	public VirtualFolderDbId getAllFilesFolder() {

		return allFiles;
	}

	public VirtualFolderDbId getAlbumFolder() {
		return albumFiles;
	}
}