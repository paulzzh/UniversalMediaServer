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
package net.pms.media;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import net.pms.Messages;
import net.pms.PMS;
import net.pms.database.MediaDatabase;
import net.pms.database.MediaTableAudioMetadata;
import net.pms.database.MediaTableFiles;
import net.pms.database.MediaTableMusicBrainzReleaseLike;
import net.pms.library.DbIdMediaType;
import net.pms.library.DbIdTypeAndIdent;
import net.pms.library.LibraryResource;
import net.pms.library.PlaylistFolder;
import net.pms.library.RealFileDbId;
import net.pms.library.virtual.VirtualFolderDbId;
import net.pms.media.audio.metadata.DoubleRecordFilter;
import net.pms.media.audio.metadata.MediaAudioMetadata;
import net.pms.media.audio.metadata.MusicBrainzAlbum;
import net.pms.renderers.Renderer;
import org.apache.commons.lang.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class resolves DLNA objects identified by databaseID's.
 */
public class DbIdResourceLocator {

	private static final Logger LOGGER = LoggerFactory.getLogger(DbIdResourceLocator.class);

	/**
	 * This class is not meant to be instantiated.
	 */
	private DbIdResourceLocator() {
	}

	public static LibraryResource locateResource(Renderer renderer, String id) {
		return getLibraryResourceByDBID(renderer, DbIdMediaType.getTypeIdentByDbid(id));
	}

	public static String encodeDbid(DbIdTypeAndIdent typeIdent) {
		try {
			return String.format("%s%s%s", DbIdMediaType.GENERAL_PREFIX, typeIdent.type.dbidPrefix,
				URLEncoder.encode(typeIdent.ident, StandardCharsets.UTF_8.toString()));
		} catch (UnsupportedEncodingException e) {
			LOGGER.warn("encode error", e);
			return String.format("%s%s%s", DbIdMediaType.GENERAL_PREFIX, typeIdent.type.dbidPrefix, typeIdent.ident);
		}
	}

	/**
	 * Navigates into a DbidResource.
	 *
	 * @param typeAndIdent Resource identified by type and database id.
	 * @return In case typeAndIdent is an item, the RealFile resource is located
	 *         and resolved. In case of a container, the container will be
	 *         created and populated.
	 */
	private static LibraryResource getLibraryResourceByDBID(Renderer renderer, DbIdTypeAndIdent typeAndIdent) {
		LibraryResource res = null;
		Connection connection = null;
		try {
			connection = MediaDatabase.getConnectionIfAvailable();
			if (connection != null) {
				try (Statement statement = connection.createStatement()) {
					String sql;
					switch (typeAndIdent.type) {
						case TYPE_AUDIO, TYPE_VIDEO, TYPE_IMAGE -> {
							sql = String.format("SELECT " + MediaTableFiles.TABLE_COL_FILENAME + " FROM " + MediaTableFiles.TABLE_NAME +
								" WHERE " + MediaTableFiles.TABLE_COL_ID + " = %s", StringEscapeUtils.escapeSql(typeAndIdent.ident));
							if (LOGGER.isTraceEnabled()) {
								LOGGER.trace(String.format("SQL AUDIO/VIDEO/IMAGE : %s", sql));
							}
							try (ResultSet resultSet = statement.executeQuery(sql)) {
								if (resultSet.next()) {
									res = new RealFileDbId(renderer, new File(resultSet.getString("FILENAME")));
									res.resolve();
								}
							}
						}
						case TYPE_PLAYLIST -> {
							sql = String.format("SELECT " + MediaTableFiles.TABLE_COL_FILENAME + " FROM " + MediaTableFiles.TABLE_NAME +
								" WHERE " + MediaTableFiles.TABLE_COL_ID + " = %s", StringEscapeUtils.escapeSql(typeAndIdent.ident));
							if (LOGGER.isTraceEnabled()) {
								LOGGER.trace(String.format("SQL PLAYLIST : %s", sql));
							}
							try (ResultSet resultSet = statement.executeQuery(sql)) {
								if (resultSet.next()) {
									res = new PlaylistFolder(renderer, new File(resultSet.getString("FILENAME")));
									res.setId(String.format("$DBID$PLAYLIST$%s", typeAndIdent.ident));
									res.resolve();
									res.refreshChildren();
								}
							}
						}
						case TYPE_ALBUM -> {
							sql = String.format("SELECT " + MediaTableFiles.TABLE_COL_FILENAME + ", " + MediaTableFiles.TABLE_COL_ID +
								", " + MediaTableFiles.TABLE_COL_MODIFIED + " FROM " + MediaTableFiles.TABLE_NAME + " LEFT OUTER JOIN " +
								MediaTableAudioMetadata.TABLE_NAME + " ON " + MediaTableFiles.TABLE_COL_ID + " = " +
								MediaTableAudioMetadata.TABLE_COL_FILEID + " WHERE ( " + MediaTableFiles.TABLE_COL_FORMAT_TYPE +
								" = 1  AND  " + MediaTableAudioMetadata.TABLE_COL_ALBUM + " = '%s')", StringEscapeUtils.escapeSql(typeAndIdent.ident));
							if (LOGGER.isTraceEnabled()) {
								LOGGER.trace(String.format("SQL AUDIO-ALBUM : %s", sql));
							}
							try (ResultSet resultSet = statement.executeQuery(sql)) {
								res = new VirtualFolderDbId(renderer, typeAndIdent.ident,
									new DbIdTypeAndIdent(DbIdMediaType.TYPE_ALBUM, typeAndIdent.ident), "");
								while (resultSet.next()) {
									LibraryResource item = new RealFileDbId(
										renderer,
										new DbIdTypeAndIdent(DbIdMediaType.TYPE_AUDIO, resultSet.getString("ID")),
										new File(resultSet.getString("FILENAME")));
									item.resolve();
									res.addChild(item);
								}
							}
						}
						case TYPE_MUSICBRAINZ_RECORDID -> {
							sql = String
								.format("SELECT " + MediaTableFiles.TABLE_COL_FILENAME + ", " + MediaTableAudioMetadata.TABLE_COL_MBID_TRACK +
									", " + MediaTableFiles.TABLE_COL_ID + ", " + MediaTableAudioMetadata.TABLE_COL_ALBUM + " FROM " +
									MediaTableFiles.TABLE_NAME + " LEFT OUTER JOIN " + MediaTableAudioMetadata.TABLE_NAME + " ON " +
									MediaTableFiles.TABLE_COL_ID + " = " + MediaTableAudioMetadata.TABLE_COL_FILEID + " " + "WHERE ( " +
									MediaTableFiles.TABLE_COL_FORMAT_TYPE + " = 1 and " + MediaTableAudioMetadata.TABLE_COL_MBID_RECORD +
									" = '%s' ) ORDER BY " + MediaTableAudioMetadata.TABLE_COL_MBID_TRACK, StringEscapeUtils.escapeSql(typeAndIdent.ident));
							if (LOGGER.isTraceEnabled()) {
								LOGGER.trace(String.format("SQL TYPE_MUSICBRAINZ_RECORDID : %s", sql));
							}
							try (ResultSet resultSet = statement.executeQuery(sql)) {
								if (resultSet.next()) {
									res = new VirtualFolderDbId(renderer, resultSet.getString("ALBUM"),
										new DbIdTypeAndIdent(DbIdMediaType.TYPE_MUSICBRAINZ_RECORDID, typeAndIdent.ident), "");
									res.setFakeParentId(
										encodeDbid(new DbIdTypeAndIdent(DbIdMediaType.TYPE_MYMUSIC_ALBUM, Messages.getString("MyAlbums"))));
									// Find "best track" logic should be
									// optimized !!
									String lastUuidTrack = "";
									do {
										String currentUuidTrack = resultSet.getString("MBID_TRACK");
										if (!currentUuidTrack.equals(lastUuidTrack)) {
											lastUuidTrack = currentUuidTrack;
											LibraryResource item = new RealFileDbId(
												renderer,
												new DbIdTypeAndIdent(DbIdMediaType.TYPE_AUDIO, resultSet.getString("ID")),
												new File(resultSet.getString("FILENAME")));
											item.resolve();
											res.addChild(item);
										}
									} while (resultSet.next());
								}
							}
						}
						case TYPE_MYMUSIC_ALBUM -> {
							sql = "SELECT " + MediaTableMusicBrainzReleaseLike.TABLE_COL_MBID_RELEASE + ", " +
								MediaTableAudioMetadata.TABLE_COL_ALBUM + ", " + MediaTableAudioMetadata.TABLE_COL_GENRE + ", " +
								MediaTableAudioMetadata.TABLE_COL_ARTIST + ", " + MediaTableAudioMetadata.TABLE_COL_MEDIA_YEAR + " FROM " +
								MediaTableMusicBrainzReleaseLike.TABLE_NAME + " JOIN " + MediaTableAudioMetadata.TABLE_NAME + " ON " +
								MediaTableMusicBrainzReleaseLike.TABLE_COL_MBID_RELEASE + " = " +
								MediaTableAudioMetadata.TABLE_COL_MBID_RECORD + ";";
							if (LOGGER.isTraceEnabled()) {
								LOGGER.trace(String.format("SQL TYPE_MYMUSIC_ALBUM : %s", sql));
							}
							DoubleRecordFilter filter = new DoubleRecordFilter();
							res = new VirtualFolderDbId(renderer, Messages.getString("MyAlbums"),
								new DbIdTypeAndIdent(DbIdMediaType.TYPE_MYMUSIC_ALBUM, Messages.getString("MyAlbums")), "");
							if (PMS.getConfiguration().displayAudioLikesInRootFolder()) {
								res.setFakeParentId("0");
							} else if (renderer.getRootFolder().getLibrary().isEnabled()) {
								res.setFakeParentId(renderer.getRootFolder().getLibrary().getAudioFolder().getId());
							} else {
								LOGGER.debug("couldn't add 'My Music' folder because the media library is not initialized.");
								return null;
							}
							try (ResultSet resultSet = statement.executeQuery(sql)) {
								while (resultSet.next()) {
									filter.addAlbum(new MusicBrainzAlbum(resultSet.getString("MBID_RELEASE"), resultSet.getString("ALBUM"),
										resultSet.getString("ARTIST"), resultSet.getInt("MEDIA_YEAR"), resultSet.getString("GENRE")));
								}
								for (MusicBrainzAlbum album : filter.getUniqueAlbumSet()) {
									VirtualFolderDbId albumFolder = new VirtualFolderDbId(renderer, album.getAlbum(),
										new DbIdTypeAndIdent(DbIdMediaType.TYPE_MUSICBRAINZ_RECORDID, album.getMbReleaseid()), "");
									appendAlbumInformation(album, albumFolder);
									res.addChild(albumFolder);
								}
							}
						}
						case TYPE_PERSON_ALL_FILES -> {
							sql = personAllFilesSql(typeAndIdent);
							if (LOGGER.isTraceEnabled()) {
								LOGGER.trace(String.format("SQL PERSON : %s", sql));
							}
							try (ResultSet resultSet = statement.executeQuery(sql)) {
								res = new VirtualFolderDbId(renderer, typeAndIdent.ident,
									new DbIdTypeAndIdent(DbIdMediaType.TYPE_ALBUM, typeAndIdent.ident), "");
								while (resultSet.next()) {
									LibraryResource item = new RealFileDbId(
										renderer,
										new DbIdTypeAndIdent(DbIdMediaType.TYPE_AUDIO, resultSet.getString("ID")),
										new File(resultSet.getString("FILENAME")));
									item.resolve();
									res.addChild(item);
								}
							}
							res.setFakeParentId(encodeDbid(new DbIdTypeAndIdent(DbIdMediaType.TYPE_PERSON, typeAndIdent.ident)));
						}
						case TYPE_PERSON, TYPE_PERSON_COMPOSER, TYPE_PERSON_CONDUCTOR -> {
							res = new VirtualFolderDbId(renderer, typeAndIdent.ident, new DbIdTypeAndIdent(typeAndIdent.type, typeAndIdent.ident),
								"");
							LibraryResource allFiles = new VirtualFolderDbId(renderer, Messages.getString("AllFiles"),
								new DbIdTypeAndIdent(DbIdMediaType.TYPE_PERSON_ALL_FILES, typeAndIdent.ident), "");
							res.addChild(allFiles);
							LibraryResource albums = new VirtualFolderDbId(renderer, Messages.getString("ByAlbum_lowercase"),
								new DbIdTypeAndIdent(DbIdMediaType.TYPE_PERSON_ALBUM, typeAndIdent.ident), "");
							res.addChild(albums);
						}
						case TYPE_PERSON_ALBUM -> {
							sql = personAlbumSql(typeAndIdent);
							res = new VirtualFolderDbId(renderer, typeAndIdent.ident,
								new DbIdTypeAndIdent(DbIdMediaType.TYPE_ALBUM, typeAndIdent.ident), "");
							try (ResultSet resultSet = statement.executeQuery(sql)) {
								while (resultSet.next()) {
									String album = resultSet.getString(1);
									res.addChild(new VirtualFolderDbId(renderer, album, new DbIdTypeAndIdent(DbIdMediaType.TYPE_PERSON_ALBUM_FILES,
										typeAndIdent.ident + DbIdMediaType.SPLIT_CHARS + album), ""));
								}
							}
							res.setFakeParentId(encodeDbid(new DbIdTypeAndIdent(DbIdMediaType.TYPE_PERSON, typeAndIdent.ident)));
						}
						case TYPE_PERSON_ALBUM_FILES -> {
							String[] identSplitted = typeAndIdent.ident.split(DbIdMediaType.SPLIT_CHARS);
							sql = personAlbumFileSql(typeAndIdent);
							try (ResultSet resultSet = statement.executeQuery(sql)) {
								res = new VirtualFolderDbId(renderer, identSplitted[1],
									new DbIdTypeAndIdent(DbIdMediaType.TYPE_ALBUM, typeAndIdent.ident), "");
								while (resultSet.next()) {
									LibraryResource item = new RealFileDbId(
										renderer,
										new DbIdTypeAndIdent(DbIdMediaType.TYPE_AUDIO, resultSet.getString("ID")),
										new File(resultSet.getString("FILENAME")));
									item.resolve();
									res.addChild(item);
								}
							}
							res.setFakeParentId(encodeDbid(new DbIdTypeAndIdent(DbIdMediaType.TYPE_PERSON_ALBUM, identSplitted[0])));
						}
						default -> throw new RuntimeException("Unknown Type");
					}
				}
			} else {
				LOGGER.error("database not available !");
			}
		} catch (SQLException e) {
			LOGGER.warn("getLibraryResourceByDBID", e);
		} finally {
			MediaDatabase.close(connection);
		}
		return res;
	}

	private static String personAlbumFileSql(DbIdTypeAndIdent typeAndIdent) {
		StringBuilder sb = new StringBuilder();
		String[] identSplitted = typeAndIdent.ident.split(DbIdMediaType.SPLIT_CHARS);

		sb.append("SELECT ").append(MediaTableFiles.TABLE_COL_FILENAME).append(", ").append(MediaTableFiles.TABLE_COL_ID).append(", ")
			.append(MediaTableFiles.TABLE_COL_MODIFIED).append(" FROM ").append(MediaTableFiles.TABLE_NAME).append(" LEFT OUTER JOIN ")
			.append(MediaTableAudioMetadata.TABLE_NAME).append(" ON ").append(MediaTableFiles.TABLE_COL_ID).append(" = ")
			.append(MediaTableAudioMetadata.TABLE_COL_FILEID).append(" ").append("WHERE (").append(MediaTableAudioMetadata.TABLE_COL_ALBUM)
			.append(" = '").append(StringEscapeUtils.escapeSql(identSplitted[1])).append("') AND ( ");
		wherePartPersonByType(identSplitted[0], sb);
		sb.append(")");
		LOGGER.debug("personAlbumFilesSql : {}", sb.toString());
		return sb.toString();
	}

	private static String personAlbumSql(DbIdTypeAndIdent typeAndIdent) {
		StringBuilder sb = new StringBuilder();
		sb.append("SELECT DISTINCT(").append(MediaTableAudioMetadata.TABLE_COL_ALBUM).append(") FROM ")
			.append(MediaTableAudioMetadata.TABLE_NAME).append(" WHERE (");
		wherePartPersonByType(typeAndIdent.ident, sb);
		sb.append(")");
		LOGGER.debug("personAlbumSql : {}", sb.toString());
		return sb.toString();
	}

	private static void wherePartPersonByType(String ident, StringBuilder sb) {
		ident = StringEscapeUtils.escapeSql(ident);
		if (ident.startsWith(DbIdMediaType.PERSON_COMPOSER_PREFIX)) {
			sb.append(MediaTableAudioMetadata.TABLE_COL_COMPOSER).append(" = '")
				.append(ident.substring(DbIdMediaType.PERSON_COMPOSER_PREFIX.length())).append("'");
			LOGGER.trace("WHERE PERSON COMPOSER");
		} else if (ident.startsWith(DbIdMediaType.PERSON_CONDUCTOR_PREFIX)) {
			sb.append(MediaTableAudioMetadata.TABLE_COL_CONDUCTOR).append(" = '")
				.append(ident.substring(DbIdMediaType.PERSON_CONDUCTOR_PREFIX.length())).append("'");
			LOGGER.trace("WHERE PERSON CONDUCTOR");
		} else if (ident.startsWith(DbIdMediaType.PERSON_ALBUMARTIST_PREFIX)) {
			sb.append(MediaTableAudioMetadata.TABLE_COL_ALBUMARTIST).append(" = '")
				.append(ident.substring(DbIdMediaType.PERSON_ALBUMARTIST_PREFIX.length())).append("'");
			LOGGER.trace("WHERE PERSON ALBUMARTIST");
		} else {
			sb.append(String.format(MediaTableAudioMetadata.TABLE_COL_ARTIST + " = '%s'", ident));
			LOGGER.trace("WHERE PERSON ARTIST");
		}
	}

	private static String personAllFilesSql(DbIdTypeAndIdent typeAndIdent) {
		StringBuilder sb = new StringBuilder();
		sb.append("SELECT ").append(MediaTableFiles.TABLE_COL_FILENAME).append(", ").append(MediaTableFiles.TABLE_COL_ID).append(", ")
			.append(MediaTableFiles.TABLE_COL_MODIFIED).append(" FROM ").append(MediaTableFiles.TABLE_NAME).append(" LEFT OUTER JOIN ")
			.append(MediaTableAudioMetadata.TABLE_NAME).append(" ON ").append(MediaTableFiles.TABLE_COL_ID).append(" = ")
			.append(MediaTableAudioMetadata.TABLE_COL_FILEID).append(" WHERE ( ");
		wherePartPersonByType(typeAndIdent.ident, sb);
		sb.append(")");
		LOGGER.debug("personAllFilesSql : {}", sb.toString());
		return sb.toString();
	}

	/**
	 * Adds album information
	 *
	 * @param album
	 * @param albumFolder
	 */
	public static void appendAlbumInformation(MusicBrainzAlbum album, VirtualFolderDbId albumFolder) {
		LOGGER.debug("adding music album information");
		MediaInfo fakeMediaInfo = new MediaInfo();
		MediaAudioMetadata fakeAudioMetadata = new MediaAudioMetadata();
		fakeAudioMetadata.setAlbum(album.getAlbum());
		fakeAudioMetadata.setArtist(album.getArtist());
		fakeAudioMetadata.setYear(album.getYear());
		fakeAudioMetadata.setGenre(album.getGenre());
		fakeMediaInfo.setAudioMetadata(fakeAudioMetadata);
		albumFolder.setMediaInfo(fakeMediaInfo);
	}

}