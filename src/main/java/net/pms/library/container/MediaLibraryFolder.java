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
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.pms.Messages;
import net.pms.PMS;
import net.pms.database.MediaDatabase;
import net.pms.database.MediaTableFiles;
import net.pms.database.MediaTableFilesStatus;
import net.pms.database.MediaTableTVSeries;
import net.pms.database.MediaTableVideoMetadata;
import net.pms.database.MediaTableVideoMetadataActors;
import net.pms.database.MediaTableVideoMetadataCountries;
import net.pms.database.MediaTableVideoMetadataDirectors;
import net.pms.database.MediaTableVideoMetadataGenres;
import net.pms.database.MediaTableVideoMetadataIMDbRating;
import net.pms.database.MediaTableVideoMetadataRated;
import net.pms.database.MediaTableVideoMetadataReleased;
import net.pms.dlna.DLNAThumbnail;
import net.pms.dlna.DLNAThumbnailInputStream;
import net.pms.library.*;
import net.pms.library.item.RealFile;
import net.pms.network.mediaserver.jupnp.support.contentdirectory.UmsContentDirectoryService;
import net.pms.renderers.Renderer;
import net.pms.util.UMSUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A MediaLibraryFolder can be populated by either virtual folders (e.g. TEXTS
 * and SEASONS) or virtual/real files (e.g. FILES and ISOS). All of these are
 * connected to SQL queries.
 *
 * When the expectedOutput is appended with "_WITH_FILTERS", Watched and Unwatched
 * variants will be added at the top.
 */
public class MediaLibraryFolder extends MediaLibraryAbstract {
	private static final Logger LOGGER = LoggerFactory.getLogger(MediaLibraryFolder.class);

	private boolean isTVSeries = false;
	private boolean isMovieFolder = false;
	private String[] sqls;
	private int[] expectedOutputs;
	private String displayNameOverride;
	private List<String> populatedVirtualFoldersListFromDb;
	private List<String> populatedFilesListFromDb;

	public MediaLibraryFolder(Renderer renderer, String name, String sql, int expectedOutput) {
		this(renderer, name, new String[]{sql}, new int[]{expectedOutput}, null, false, false);
	}

	public MediaLibraryFolder(Renderer renderer, String name, String[] sql, int[] expectedOutput) {
		this(renderer, name, sql, expectedOutput, null, false, false);
	}

	public MediaLibraryFolder(Renderer renderer, String name, String sql, int expectedOutput, String nameToDisplay) {
		this(renderer, name, new String[]{sql}, new int[]{expectedOutput}, nameToDisplay, false, false);
	}

	public MediaLibraryFolder(Renderer renderer, String name, String[] sql, int[] expectedOutput, String nameToDisplay) {
		this(renderer, name, sql, expectedOutput, nameToDisplay, false, false);
	}

	public MediaLibraryFolder(Renderer renderer, String name, String[] sql, int[] expectedOutput, String nameToDisplay, boolean isTVSeriesFolder, boolean isMoviesFolder) {
		super(renderer, name, null);
		this.sqls = sql;
		this.expectedOutputs = expectedOutput;
		if (nameToDisplay != null) {
			this.displayNameOverride = nameToDisplay;
		}
		if (isTVSeriesFolder) {
			this.isTVSeries = true;
		}
		if (isMoviesFolder) {
			this.isMovieFolder = true;
		}
	}

	@Override
	public void discoverChildren() {
		doRefreshChildren();
		setDiscovered(true);
	}

	private String transformSQL(String sql) {
		int i = 1;
		LibraryResource resource = this;
		sql = sql.replace("${0}", transformName(getName()));
		while (resource.getParent() != null) {
			resource = resource.getParent();
			sql = sql.replace("${" + i + "}", transformName(resource.getName()));
			i++;
		}

		return sql;
	}

	private String transformName(String name) {
		if (name.equals(MediaTableFiles.NONAME)) {
			name = "";
		}
		name = name.replace("'", "''"); // issue 448
		return name;
	}

	/**
	 * Whether the contents of this virtual folder should be refreshed.
	 *
	 * @return true if the old cached SQL result matches the new one.
	 */
	@Override
	public boolean isRefreshNeeded() {
		Connection connection = null;
		try {
			connection = MediaDatabase.getConnectionIfAvailable();
			if (connection != null && sqls.length > 0) {
				String sql = sqls[0];
				int expectedOutput = expectedOutputs[0];
				if (sql != null) {
					sql = transformSQL(sql);

					if (
						expectedOutput == EPISODES ||
						expectedOutput == EPISODES_WITHIN_SEASON ||
						expectedOutput == FILES ||
						expectedOutput == FILES_NOSORT ||
						expectedOutput == FILES_NOSORT_DEDUPED ||
						expectedOutput == FILES_WITH_FILTERS ||
						expectedOutput == ISOS ||
						expectedOutput == ISOS_WITH_FILTERS ||
						expectedOutput == PLAYLISTS
					) {
						return !UMSUtils.isListsEqual(populatedFilesListFromDb, MediaTableFiles.getStrings(connection, sql));
					} else if (isTextOutputExpected(expectedOutput)) {
						return !UMSUtils.isListsEqual(populatedVirtualFoldersListFromDb, MediaTableFiles.getStrings(connection, sql));
					}
				}
			} else {
				//database not available
				return false;
			}
		} finally {
			MediaDatabase.close(connection);
		}
		return true;
	}

	private static List<String> getTVSeriesQueries(String tableName, String columnName) {
		List<String> queries = new ArrayList<>();
		queries.add(SELECT + columnName + FROM + tableName + WHERE + MediaTableTVSeries.CHILD_ID + IS_NOT_NULL + ORDER_BY + columnName + ASC);
		queries.add(SELECT + MediaTableTVSeries.TABLE_COL_TITLE + FROM + MediaTableTVSeries.TABLE_NAME + LEFT_JOIN + tableName + ON + MediaTableTVSeries.TABLE_COL_ID + EQUAL + tableName + ".TVSERIESID" + WHERE + columnName + EQUAL + "'${0}'" + ORDER_BY + MediaTableTVSeries.TABLE_COL_TITLE + ASC);
		queries.add(SELECT + "*" + FROM_FILES_VIDEOMETA + WHERE + FORMAT_TYPE_VIDEO + AND + TVEPISODE_CONDITION + AND + MediaTableVideoMetadata.TABLE_COL_MOVIEORSHOWNAME + EQUAL + "'${0}'" + ORDER_BY + MediaTableVideoMetadata.TABLE_COL_TVEPISODENUMBER);
		return queries;
	}

	private static String getFirstNonTVSeriesQuery(String firstSql, String tableName, String columnName) {
		int indexAfterFromInFirstQuery = firstSql.indexOf(FROM_FILES) + FROM_FILES.length();

		String selectSection = SELECT_DISTINCT + columnName + FROM_FILES;
		String orderBySection = ORDER_BY + columnName + ASC;

		// These queries join tables
		StringBuilder query = new StringBuilder(firstSql);

		// If the query does not already join the right metadata table, do that now
		if (!firstSql.contains(LEFT_JOIN + tableName)) {
			String joinSection = LEFT_JOIN + tableName + ON + MediaTableFiles.TABLE_COL_ID + EQUAL + tableName + "." + MediaTableVideoMetadata.COL_FILEID;
			query.insert(indexAfterFromInFirstQuery, joinSection);
		}

		indexAfterFromInFirstQuery = firstSql.indexOf(FROM_FILES) + FROM_FILES.length();

		query.replace(0, indexAfterFromInFirstQuery, selectSection);

		int indexBeforeOrderByInFirstActorsQuery = query.indexOf(ORDER_BY);
		query.replace(indexBeforeOrderByInFirstActorsQuery, query.length(), orderBySection);

		return query.toString();
	}

	private static String getSubsequentNonTVSeriesQuery(String sql, String tableName, String columnName, int i) {
		StringBuilder query = new StringBuilder(sql);
		int indexAfterFrom = sql.indexOf(FROM_FILES) + FROM_FILES.length();
		String condition = columnName + EQUAL + "'${0}'" + AND;
		if (!sql.contains(LEFT_JOIN + tableName)) {
			String joinSection = LEFT_JOIN + tableName + ON + MediaTableFiles.TABLE_COL_ID + EQUAL + tableName + "." + MediaTableVideoMetadata.COL_FILEID;
			query.insert(indexAfterFrom, joinSection);
		}
		int indexAfterWhere = query.indexOf(WHERE) + WHERE.length();
		String replacedCondition = condition.replace("${0}", "${" + i + "}");
		query.insert(indexAfterWhere, replacedCondition);
		return query.toString();
	}

	/**
	 * Removes all children and re-adds them
	 */
	@Override
	public void doRefreshChildren() {
		List<File> filesListFromDb = null;
		List<String> virtualFoldersListFromDb = null;

		List<String> unwatchedSqls = new ArrayList<>();
		List<String> watchedSqls = new ArrayList<>();

		List<String> actorsSqls = new ArrayList<>();
		List<String> countriesSqls = new ArrayList<>();
		List<String> directorsSqls = new ArrayList<>();
		List<String> genresSqls = new ArrayList<>();
		List<String> ratedSqls = new ArrayList<>();
		List<String> releasedSqls = new ArrayList<>();

		StringBuilder seasonsQuery = new StringBuilder();

		int expectedOutput = 0;
		String firstSql = null;
		if (sqls.length > 0) {
			Connection connection = null;
			try {
				connection = MediaDatabase.getConnectionIfAvailable();
				if (connection != null) {
					firstSql = sqls[0];
					expectedOutput = expectedOutputs[0];
					if (firstSql != null) {
						firstSql = transformSQL(firstSql);
						switch (expectedOutput) {
							case FILES, FILES_NOSORT, PLAYLISTS, ISOS, EPISODES_WITHIN_SEASON -> {
								firstSql = firstSql.replaceAll(SELECT_DISTINCT_TVSEASON, SELECT + "*" + FROM_FILES_VIDEOMETA);
								filesListFromDb = MediaTableFiles.getFiles(connection, firstSql);
								populatedFilesListFromDb = MediaTableFiles.getStrings(connection, firstSql);
							}
							case FILES_NOSORT_DEDUPED -> {
								populatedFilesListFromDb = new ArrayList<>();
								filesListFromDb = new ArrayList<>();
								for (File item : MediaTableFiles.getFiles(connection, firstSql)) {
									if (!populatedFilesListFromDb.contains(item.getAbsolutePath())) {
										filesListFromDb.add(item);
										populatedFilesListFromDb.add(item.getAbsolutePath());
									}
								}
							}
							case EPISODES -> {
								filesListFromDb = MediaTableFiles.getFiles(connection, firstSql);
								populatedFilesListFromDb = MediaTableFiles.getStrings(connection, firstSql);

								// Build the season filter folders
								int indexAfterFromInFirstQuery = firstSql.indexOf(FROM_FILES) + FROM_FILES.length();
								int indexAtJointure = firstSql.indexOf(MediaTableFiles.SQL_LEFT_JOIN_TABLE_VIDEO_METADATA);
								if (indexAtJointure > 0) {
									indexAfterFromInFirstQuery = indexAtJointure + MediaTableFiles.SQL_LEFT_JOIN_TABLE_VIDEO_METADATA.length();
								}
								String orderBySection = ORDER_BY + MediaTableVideoMetadata.TABLE_COL_TVSEASON;

								seasonsQuery.append(firstSql);
								seasonsQuery.replace(0, indexAfterFromInFirstQuery, SELECT_DISTINCT_TVSEASON);

								int indexBeforeOrderByInFirstQuery = seasonsQuery.indexOf(ORDER_BY);
								seasonsQuery.replace(indexBeforeOrderByInFirstQuery, seasonsQuery.length(), orderBySection);
								virtualFoldersListFromDb = MediaTableFiles.getStrings(connection, seasonsQuery.toString());
								populatedVirtualFoldersListFromDb = virtualFoldersListFromDb;
							}
							case TEXTS, TEXTS_NOSORT, SEASONS, TVSERIES, TVSERIES_NOSORT, MOVIE_FOLDERS -> {
								virtualFoldersListFromDb = MediaTableFiles.getStrings(connection, firstSql);
								populatedVirtualFoldersListFromDb = virtualFoldersListFromDb;
							}
							case FILES_WITH_FILTERS, ISOS_WITH_FILTERS, TEXTS_NOSORT_WITH_FILTERS, TEXTS_WITH_FILTERS, TVSERIES_WITH_FILTERS -> {
								if (expectedOutput == TEXTS_NOSORT_WITH_FILTERS || expectedOutput == TEXTS_WITH_FILTERS || expectedOutput == TVSERIES_WITH_FILTERS) {
									virtualFoldersListFromDb = MediaTableFiles.getStrings(connection, firstSql);
									populatedVirtualFoldersListFromDb = virtualFoldersListFromDb;
								} else if (expectedOutput == FILES_WITH_FILTERS || expectedOutput == ISOS_WITH_FILTERS) {
									filesListFromDb = MediaTableFiles.getFiles(connection, firstSql);
									populatedFilesListFromDb = MediaTableFiles.getStrings(connection, firstSql);
								}

								if (!firstSql.toLowerCase().startsWith("select")) {
									if (expectedOutput == TEXTS_NOSORT_WITH_FILTERS || expectedOutput == TEXTS_WITH_FILTERS || expectedOutput == TVSERIES_WITH_FILTERS) {
										firstSql = SELECT + MediaTableFiles.TABLE_COL_FILENAME + FROM + MediaTableFiles.TABLE_NAME + WHERE + firstSql;
									}
									if (expectedOutput == FILES_WITH_FILTERS || expectedOutput == ISOS_WITH_FILTERS) {
										firstSql = SELECT + MediaTableFiles.TABLE_COL_FILENAME + ", " + MediaTableFiles.TABLE_COL_MODIFIED + FROM + MediaTableFiles.TABLE_NAME + WHERE + firstSql;
									}
								}

								// This block adds the first SQL query for non-TV series, and all queries for TV series
								if (renderer.getUmsConfiguration().isUseInfoFromIMDb()) {
									/*
									* With TV series we manually add the SQL statements, otherwise we
									* attempt to modify the incoming statements to make filtering versions.
									*/
									if (expectedOutput == TVSERIES_WITH_FILTERS) {
										actorsSqls = getTVSeriesQueries(MediaTableVideoMetadataActors.TABLE_NAME, MediaTableVideoMetadataActors.TABLE_COL_ACTOR);
										countriesSqls = getTVSeriesQueries(MediaTableVideoMetadataCountries.TABLE_NAME, MediaTableVideoMetadataCountries.TABLE_COL_COUNTRY);
										directorsSqls = getTVSeriesQueries(MediaTableVideoMetadataDirectors.TABLE_NAME, MediaTableVideoMetadataDirectors.TABLE_COL_DIRECTOR);
										genresSqls = getTVSeriesQueries(MediaTableVideoMetadataGenres.TABLE_NAME, MediaTableVideoMetadataGenres.TABLE_COL_GENRE);
										ratedSqls = getTVSeriesQueries(MediaTableVideoMetadataRated.TABLE_NAME, MediaTableVideoMetadataRated.TABLE_COL_RATED);
										releasedSqls = getTVSeriesQueries(MediaTableVideoMetadataReleased.TABLE_NAME, RELEASEDATE_FORMATED);
									} else {
										actorsSqls.add(getFirstNonTVSeriesQuery(firstSql, MediaTableVideoMetadataActors.TABLE_NAME, MediaTableVideoMetadataActors.TABLE_COL_ACTOR));
										countriesSqls.add(getFirstNonTVSeriesQuery(firstSql, MediaTableVideoMetadataCountries.TABLE_NAME, MediaTableVideoMetadataCountries.TABLE_COL_COUNTRY));
										directorsSqls.add(getFirstNonTVSeriesQuery(firstSql, MediaTableVideoMetadataDirectors.TABLE_NAME, MediaTableVideoMetadataDirectors.TABLE_COL_DIRECTOR));
										genresSqls.add(getFirstNonTVSeriesQuery(firstSql, MediaTableVideoMetadataGenres.TABLE_NAME, MediaTableVideoMetadataGenres.TABLE_COL_GENRE));
										ratedSqls.add(getFirstNonTVSeriesQuery(firstSql, MediaTableVideoMetadataRated.TABLE_NAME, MediaTableVideoMetadataRated.TABLE_COL_RATED));
										releasedSqls.add(getFirstNonTVSeriesQuery(firstSql, MediaTableVideoMetadataReleased.TABLE_NAME, RELEASEDATE_FORMATED));
									}
								}

								// This block adds the second+ queries by modifying what was passed in, allowing this to be somewhat dynamic
								int i = 0;
								for (String sql : sqls) {
									if (!sql.toLowerCase().startsWith("select") && !sql.toLowerCase().startsWith("with")) {
										if (expectedOutput == TEXTS_NOSORT_WITH_FILTERS || expectedOutput == TEXTS_WITH_FILTERS || expectedOutput == TVSERIES_WITH_FILTERS) {
											sql = SELECT + MediaTableFiles.TABLE_COL_FILENAME + FROM + MediaTableFiles.TABLE_NAME + WHERE + sql;
										}
										if (expectedOutput == FILES_WITH_FILTERS || expectedOutput == ISOS_WITH_FILTERS) {
											sql = SELECT + MediaTableFiles.TABLE_COL_FILENAME + ", " + MediaTableFiles.TABLE_COL_MODIFIED + FROM + MediaTableFiles.TABLE_NAME + WHERE + sql;
										}
									}
									int indexAfterFrom = sql.indexOf(FROM_FILES) + FROM_FILES.length();

									// If the query does not already join the FILES_STATUS table, do that now
									StringBuilder sqlWithJoin = new StringBuilder(sql);
									if (!sql.contains(LEFT_JOIN + MediaTableFilesStatus.TABLE_NAME)) {
										sqlWithJoin.insert(indexAfterFrom, MediaTableFiles.SQL_LEFT_JOIN_TABLE_FILES_STATUS);
									}

									int indexAfterWhere = sqlWithJoin.indexOf(WHERE) + WHERE.length();

									StringBuilder unwatchedSql = new StringBuilder(sqlWithJoin);
									unwatchedSql.insert(indexAfterWhere, getUnWatchedCondition(renderer.getAccountUserId()) + AND);
									unwatchedSqls.add(unwatchedSql.toString());

									StringBuilder watchedSql = new StringBuilder(sqlWithJoin);
									watchedSql.insert(indexAfterWhere, getWatchedCondition(renderer.getAccountUserId()) + AND);
									watchedSqls.add(watchedSql.toString());

									// Adds modified versions of the query that filter by metadata
									if (renderer.getUmsConfiguration().isUseInfoFromIMDb() && expectedOutput != TVSERIES_WITH_FILTERS) {
										actorsSqls.add(getSubsequentNonTVSeriesQuery(sql, MediaTableVideoMetadataActors.TABLE_NAME, MediaTableVideoMetadataActors.TABLE_COL_ACTOR, i));
										countriesSqls.add(getSubsequentNonTVSeriesQuery(sql, MediaTableVideoMetadataCountries.TABLE_NAME, MediaTableVideoMetadataCountries.TABLE_COL_COUNTRY, i));
										directorsSqls.add(getSubsequentNonTVSeriesQuery(sql, MediaTableVideoMetadataDirectors.TABLE_NAME, MediaTableVideoMetadataDirectors.TABLE_COL_DIRECTOR, i));
										genresSqls.add(getSubsequentNonTVSeriesQuery(sql, MediaTableVideoMetadataGenres.TABLE_NAME, MediaTableVideoMetadataGenres.TABLE_COL_GENRE, i));
										ratedSqls.add(getSubsequentNonTVSeriesQuery(sql, MediaTableVideoMetadataRated.TABLE_NAME, MediaTableVideoMetadataRated.TABLE_COL_RATED, i));
										releasedSqls.add(getSubsequentNonTVSeriesQuery(sql, MediaTableVideoMetadataReleased.TABLE_NAME, RELEASEDATE_FORMATED, i));
									}
									i++;
								}
							}
							default -> {
								// nothing to do
							}
						}
						// Output is files
						// Output is folders
						// Output is both
					}
				}
			} finally {
				MediaDatabase.close(connection);
			}
		}
		List<File> newFiles = new ArrayList<>();
		List<String> newVirtualFolders = new ArrayList<>();
		List<LibraryResource> oldFiles = new ArrayList<>();
		List<LibraryResource> oldVirtualFolders = new ArrayList<>();

		if (filesListFromDb != null) {
			if (expectedOutput != FILES_NOSORT && expectedOutput != FILES_NOSORT_DEDUPED) {
				UMSUtils.sortFiles(filesListFromDb, PMS.getConfiguration().getSortMethod(null), expectedOutput == EPISODES);
			}

			getChildren().forEach(oldFiles::add);

			for (File file : filesListFromDb) {
				newFiles.add(file);
			}
		}

		if (virtualFoldersListFromDb != null) {
			if (expectedOutput != TEXTS_NOSORT && expectedOutput != TEXTS_NOSORT_WITH_FILTERS && expectedOutput != TVSERIES_NOSORT) {
				UMSUtils.sortStrings(virtualFoldersListFromDb, PMS.getConfiguration().getSortMethod(null));
			}

			getChildren().forEach(oldVirtualFolders::add);

			for (String f : virtualFoldersListFromDb) {
				newVirtualFolders.add(f);
			}
		}

		oldFiles.forEach(fileResource -> getChildren().remove(fileResource));

		oldVirtualFolders.forEach(virtualFolderResource -> getChildren().remove(virtualFolderResource));

		// Add filters at the top
		if (expectedOutput == TEXTS_NOSORT_WITH_FILTERS || expectedOutput == TEXTS_WITH_FILTERS || expectedOutput == FILES_WITH_FILTERS || expectedOutput == TVSERIES_WITH_FILTERS) {
			// Convert the expectedOutputs to unfiltered versions
			int[] filteredExpectedOutputs = expectedOutputs.clone();
			switch (filteredExpectedOutputs[0]) {
				case FILES_WITH_FILTERS:
					filteredExpectedOutputs[0] = FILES;
					break;
				case ISOS_WITH_FILTERS:
					filteredExpectedOutputs[0] = ISOS;
					break;
				case TEXTS_WITH_FILTERS:
					filteredExpectedOutputs[0] = TEXTS;
					break;
				case TVSERIES_WITH_FILTERS:
					filteredExpectedOutputs[0] = TVSERIES;
					break;
				case TEXTS_NOSORT_WITH_FILTERS:
					filteredExpectedOutputs[0] = TEXTS_NOSORT;
					break;
				default:
					break;
			}

			int[] filteredExpectedOutputsWithPrependedTexts = filteredExpectedOutputs.clone();
			filteredExpectedOutputsWithPrependedTexts = ArrayUtils.insert(0, filteredExpectedOutputsWithPrependedTexts, TEXTS);

			if (!unwatchedSqls.isEmpty() && !watchedSqls.isEmpty()) {
				LibraryContainer filterByProgress = new LibraryContainer(renderer, Messages.getString("FilterByProgress"), null);
				filterByProgress.addChild(new MediaLibraryFolder(
					renderer,
					Messages.getString("Unwatched"),
					unwatchedSqls.toArray(String[]::new),
					filteredExpectedOutputs
				));
				filterByProgress.addChild(new MediaLibraryFolder(
					renderer,
					Messages.getString("Watched"),
					watchedSqls.toArray(String[]::new),
					filteredExpectedOutputs
				));
				addChild(filterByProgress);
			}
			if (!genresSqls.isEmpty()) {
				LibraryContainer filterByInformation = new LibraryContainer(renderer, Messages.getString("FilterByInformation"), null);
				filterByInformation.addChild(new MediaLibraryFolder(
					renderer,
					Messages.getString("Actors"),
					actorsSqls.toArray(String[]::new),
					filteredExpectedOutputsWithPrependedTexts
				));
				filterByInformation.addChild(new MediaLibraryFolder(
					renderer,
					Messages.getString("Country"),
					countriesSqls.toArray(String[]::new),
					filteredExpectedOutputsWithPrependedTexts
				));
				filterByInformation.addChild(new MediaLibraryFolder(
					renderer,
					Messages.getString("Director"),
					directorsSqls.toArray(String[]::new),
					filteredExpectedOutputsWithPrependedTexts
				));
				filterByInformation.addChild(new MediaLibraryFolder(
					renderer,
					Messages.getString("Genres"),
					genresSqls.toArray(String[]::new),
					filteredExpectedOutputsWithPrependedTexts
				));
				filterByInformation.addChild(new MediaLibraryFolder(
					renderer,
					Messages.getString("Rated"),
					ratedSqls.toArray(String[]::new),
					filteredExpectedOutputsWithPrependedTexts
				));
				filterByInformation.addChild(new MediaLibraryFolder(
					renderer,
					Messages.getString("Released"),
					releasedSqls.toArray(String[]::new),
					filteredExpectedOutputsWithPrependedTexts
				));
				LOGGER.trace("filteredExpectedOutputsWithPrependedTexts: " + Arrays.toString(filteredExpectedOutputsWithPrependedTexts));
				LOGGER.trace("genresSqls: " + genresSqls.toString());
				addChild(filterByInformation);
			}
		}

		// Skip adding season folders if there is only one season
		if (!(expectedOutput == EPISODES && newVirtualFolders.size() == 1)) {
			for (String virtualFolderName : newVirtualFolders) {
				if (isTextOutputExpected(expectedOutput)) {
					String[] sqls2 = new String[sqls.length - 1];
					int[] expectedOutputs2 = new int[expectedOutputs.length - 1];
					System.arraycopy(sqls, 1, sqls2, 0, sqls2.length);
					System.arraycopy(expectedOutputs, 1, expectedOutputs2, 0, expectedOutputs2.length);

					String nameToDisplay = null;
					if (expectedOutput == EPISODES) {
						expectedOutputs2 = new int[]{MediaLibraryFolder.EPISODES_WITHIN_SEASON};
						StringBuilder episodesWithinSeasonQuery = new StringBuilder(sqls[0]);

						int indexAfterWhere = episodesWithinSeasonQuery.indexOf(WHERE) + WHERE.length();
						String condition = MediaTableVideoMetadata.TABLE_COL_TVSEASON + EQUAL + "'" + virtualFolderName + "'" + AND;
						episodesWithinSeasonQuery.insert(indexAfterWhere, condition);

						sqls2 = new String[] {transformSQL(episodesWithinSeasonQuery.toString())};
						if (virtualFolderName.length() != 4) {
							nameToDisplay = Messages.getString("Season") + " " + virtualFolderName;
						}
					}

					/**
					 * Handle entries that have no value in the joined table
					 * Converts e.g. FILES LEFT JOIN VIDEO_METADATA_GENRES ON FILES.FILENAME = VIDEO_METADATA_GENRES.FILENAME WHERE VIDEO_METADATA_GENRES.GENRE = ''
					 * To e.g. FILES LEFT JOIN VIDEO_METADATA_GENRES ON FILES.FILENAME = VIDEO_METADATA_GENRES.FILENAME WHERE VIDEO_METADATA_GENRES.FILENAME IS NULL
					 *
					 * @todo this doesn't work for TV series because we are querying by the external tables. fix that
					 */
					if (expectedOutput == TEXTS || expectedOutput == TEXTS_NOSORT) {
						LibraryResource resource = this;

						if (resource.getName() != null && "###".equals(virtualFolderName)) {
							if (resource.getName().equals(Messages.getString("Actors"))) {
								for (int i = 0; i < sqls2.length; i++) {
									sqls2[i] = sqls2[i].replace(WHERE + MediaTableVideoMetadataActors.TABLE_COL_ACTOR + EQUAL + "'${" + i + "}'", WHERE + MediaTableVideoMetadataActors.TABLE_COL_FILEID + IS_NULL);
								}
								nameToDisplay = "Unknown";
							} else if (resource.getName().equals(Messages.getString("Country"))) {
								for (int i = 0; i < sqls2.length; i++) {
									sqls2[i] = sqls2[i].replace(WHERE + MediaTableVideoMetadataCountries.TABLE_COL_COUNTRY + EQUAL + "'${" + i + "}'", WHERE + MediaTableVideoMetadataCountries.TABLE_COL_FILEID + IS_NULL);
								}
								nameToDisplay = "Unknown";
							} else if (resource.getName().equals(Messages.getString("Director"))) {
								for (int i = 0; i < sqls2.length; i++) {
									sqls2[i] = sqls2[i].replace(WHERE + MediaTableVideoMetadataDirectors.TABLE_COL_DIRECTOR + EQUAL + "'${" + i + "}'", WHERE + MediaTableVideoMetadataDirectors.TABLE_COL_FILEID + IS_NULL);
								}
								nameToDisplay = "Unknown";
							} else if (resource.getName().equals(Messages.getString("Genres"))) {
								for (int i = 0; i < sqls2.length; i++) {
									sqls2[i] = sqls2[i].replace(WHERE + MediaTableVideoMetadataGenres.TABLE_COL_GENRE + EQUAL + "'${" + i + "}'", WHERE + MediaTableVideoMetadataGenres.TABLE_COL_FILEID + IS_NULL);
								}
								nameToDisplay = "Unknown";
							} else if (resource.getName().equals(Messages.getString("Rated"))) {
								for (int i = 0; i < sqls2.length; i++) {
									sqls2[i] = sqls2[i].replace(WHERE + MediaTableVideoMetadataRated.TABLE_COL_RATED + EQUAL + "'${" + i + "}'", WHERE + MediaTableVideoMetadataRated.TABLE_COL_FILEID + IS_NULL);
								}
								nameToDisplay = "Unknown";
							} else if (resource.getName().equals(Messages.getString("Released"))) {
								for (int i = 0; i < sqls2.length; i++) {
									sqls2[i] = sqls2[i].replace(WHERE + RELEASEDATE_FORMATED + EQUAL + "'${" + i + "}'", WHERE + MediaTableVideoMetadataReleased.TABLE_COL_FILEID + IS_NULL);
								}
								nameToDisplay = "Unknown";
							}
						}
					}
					boolean isExpectedTVSeries = expectedOutput == TVSERIES || expectedOutput == TVSERIES_NOSORT || expectedOutput == TVSERIES_WITH_FILTERS;
					boolean isExpectedMovieFolder = expectedOutput == MOVIE_FOLDERS;
					addChild(new MediaLibraryFolder(renderer, virtualFolderName, sqls2, expectedOutputs2, nameToDisplay, isExpectedTVSeries, isExpectedMovieFolder));
				}
			}
		}

		// Recommendations for TV series, episodes and movies
		if (expectedOutput == EPISODES) {
			LibraryContainer recommendations = new MediaLibraryFolder(
				renderer,
				Messages.getString("Recommendations"),
				new String[]{
					"WITH ratedSubquery AS (" +
						SELECT + MediaTableVideoMetadataRated.TABLE_COL_RATED + FROM + MediaTableVideoMetadataRated.TABLE_NAME +
						MediaTableVideoMetadataRated.SQL_LEFT_JOIN_TABLE_TV_SERIES +
						WHERE + MediaTableTVSeries.TABLE_COL_TITLE + EQUAL + MediaDatabase.sqlQuote(getName()) +
						LIMIT_1 +
					"), " +
					"genresSubquery AS (" +
						SELECT + MediaTableVideoMetadataGenres.TABLE_COL_GENRE + FROM + MediaTableVideoMetadataGenres.TABLE_NAME +
						MediaTableVideoMetadataGenres.SQL_LEFT_JOIN_TABLE_TV_SERIES +
						WHERE + MediaTableTVSeries.TABLE_COL_TITLE + EQUAL + MediaDatabase.sqlQuote(getName()) +
					") " +
					SELECT_DISTINCT +
						MediaTableTVSeries.TABLE_COL_TITLE + ", " +
						MediaTableVideoMetadataIMDbRating.TABLE_COL_IMDBRATING + ", " +
						MediaTableVideoMetadataGenres.TABLE_COL_GENRE + ", " +
						MediaTableVideoMetadataRated.TABLE_COL_RATED +
					FROM +
						"ratedSubquery, " +
						"genresSubquery, " +
						MediaTableTVSeries.TABLE_NAME +
						MediaTableTVSeries.SQL_LEFT_JOIN_TABLE_VIDEO_METADATA_GENRES +
						MediaTableTVSeries.SQL_LEFT_JOIN_TABLE_VIDEO_METADATA_RATED +
						MediaTableTVSeries.SQL_LEFT_JOIN_TABLE_VIDEO_METADATA_IMDB_RATING +
					WHERE +
						MediaTableTVSeries.TABLE_COL_TITLE + " != " + MediaDatabase.sqlQuote(getName()) + AND +
						MediaTableVideoMetadataGenres.TABLE_COL_GENRE + " IN (genresSubquery." + MediaTableVideoMetadataGenres.COL_GENRE + ")" + AND +
						MediaTableVideoMetadataRated.TABLE_COL_RATED  + EQUAL + "ratedSubquery." + MediaTableVideoMetadataRated.COL_RATED +
					ORDER_BY + MediaTableVideoMetadataIMDbRating.TABLE_COL_IMDBRATING + DESC,
					SELECT + "*" + FROM_FILES_VIDEOMETA + WHERE + FORMAT_TYPE_VIDEO + AND + TVEPISODE_CONDITION + AND + MediaTableVideoMetadata.TABLE_COL_MOVIEORSHOWNAME + EQUAL + "'${0}'" + ORDER_BY + MediaTableVideoMetadata.TABLE_COL_TVSEASON + ", " + MediaTableVideoMetadata.TABLE_COL_TVEPISODENUMBER
				},
				new int[]{MediaLibraryFolder.TVSERIES_NOSORT, MediaLibraryFolder.EPISODES}
			);
			addChild(recommendations);
		} else if (expectedOutput == FILES_WITH_FILTERS) {
			if (firstSql != null) {
				if (firstSql.startsWith(SELECT + MediaTableFiles.TABLE_COL_FILENAME + ", " + MediaTableFiles.TABLE_COL_MODIFIED)) {
					firstSql = firstSql.replaceFirst(SELECT + MediaTableFiles.TABLE_COL_FILENAME + ", " + MediaTableFiles.TABLE_COL_MODIFIED, SELECT + MediaTableVideoMetadata.TABLE_COL_MOVIEORSHOWNAME);
				}

				LibraryContainer recommendations = new MediaLibraryFolder(
					renderer,
					Messages.getString("Recommendations"),
					new String[]{
						firstSql,
						"WITH ratedSubquery AS (" +
							SELECT + MediaTableVideoMetadataRated.TABLE_COL_RATED + FROM + MediaTableVideoMetadataRated.TABLE_NAME +
							MediaTableVideoMetadataRated.SQL_LEFT_JOIN_TABLE_VIDEO_METADATA +
							WHERE + MediaTableVideoMetadata.TABLE_COL_MOVIEORSHOWNAME + EQUAL + "'${0}'" +
							LIMIT_1 +
						"), " +
						"genresSubquery AS (" +
							SELECT + MediaTableVideoMetadataGenres.TABLE_COL_GENRE + FROM + MediaTableVideoMetadataGenres.TABLE_NAME +
							MediaTableVideoMetadataGenres.SQL_LEFT_JOIN_TABLE_VIDEO_METADATA +
							WHERE + MediaTableVideoMetadata.TABLE_COL_MOVIEORSHOWNAME + EQUAL + "'${0}'" +
						") " +
						SELECT_DISTINCT +
							MediaTableVideoMetadata.TABLE_COL_MOVIEORSHOWNAME + ", " +
							MediaTableFiles.TABLE_NAME + ".*, " +
							MediaTableVideoMetadataIMDbRating.TABLE_COL_IMDBRATING + ", " +
							MediaTableVideoMetadataGenres.TABLE_COL_GENRE + ", " +
							MediaTableVideoMetadataRated.TABLE_COL_RATED +
						FROM +
							"ratedSubquery, " +
							"genresSubquery, " +
							MediaTableFiles.TABLE_NAME +
							MediaTableFiles.SQL_LEFT_JOIN_TABLE_VIDEO_METADATA +
							MediaTableVideoMetadata.SQL_LEFT_JOIN_TABLE_VIDEO_METADATA_GENRES +
							MediaTableVideoMetadata.SQL_LEFT_JOIN_TABLE_VIDEO_METADATA_RATED +
							MediaTableVideoMetadata.SQL_LEFT_JOIN_TABLE_VIDEO_METADATA_IMDB_RATING +
						WHERE +
							MediaTableVideoMetadata.TABLE_COL_MOVIEORSHOWNAME + " != '${0}'" + AND +
							MediaTableVideoMetadataGenres.TABLE_COL_GENRE + " IN (genresSubquery." + MediaTableVideoMetadataGenres.COL_GENRE + ")" + AND +
							MediaTableVideoMetadataRated.TABLE_COL_RATED  + EQUAL + "ratedSubquery." + MediaTableVideoMetadataRated.COL_RATED +
						ORDER_BY + MediaTableVideoMetadataIMDbRating.TABLE_COL_IMDBRATING + DESC
					},
					new int[]{MediaLibraryFolder.MOVIE_FOLDERS, MediaLibraryFolder.FILES_NOSORT_DEDUPED}
				);
				addChild(recommendations);
			}
		}

		for (File file : newFiles) {
			if (renderer.hasShareAccess(file)) {
				switch (expectedOutput) {
					case FILES, FILES_NOSORT, FILES_NOSORT_DEDUPED, FILES_WITH_FILTERS -> addChild(new RealFile(renderer, file));
					case EPISODES -> addChild(new RealFile(renderer, file, false, true));
					case EPISODES_WITHIN_SEASON -> addChild(new RealFile(renderer, file, true));
					case PLAYLISTS -> addChild(new PlaylistFolder(renderer, file));
					case ISOS, ISOS_WITH_FILTERS -> addChild(new DVDISOFile(renderer, file));
					default -> {
						// nothing to do
					}
				}
			}
		}

		if (isDiscovered()) {
			UmsContentDirectoryService.bumpSystemUpdateId();
		}
	}

	/**
	 * @param expectedOutput
	 * @return whether any text output is expected (can be in addition to file output)
	 */
	public boolean isTextOutputExpected(int expectedOutput) {
		return expectedOutput == TEXTS ||
			expectedOutput == TEXTS_NOSORT ||
			expectedOutput == TEXTS_NOSORT_WITH_FILTERS ||
			expectedOutput == TEXTS_WITH_FILTERS ||
			expectedOutput == SEASONS ||
			expectedOutput == TVSERIES_WITH_FILTERS ||
			expectedOutput == TVSERIES_NOSORT ||
			expectedOutput == TVSERIES ||
			expectedOutput == EPISODES ||
			expectedOutput == MOVIE_FOLDERS;
	}

	@Override
	public String getDisplayNameBase() {
		if (StringUtils.isNotBlank(displayNameOverride)) {
			return displayNameOverride;
		}

		return super.getDisplayNameBase();
	}

	public boolean isTVSeries() {
		return isTVSeries;
	}

	public void setIsTVSeries(boolean value) {
		isTVSeries = value;
	}

	public boolean isMovieFolder() {
		return isMovieFolder;
	}

	public void setIsMovieFolder(boolean value) {
		isMovieFolder = value;
	}

	/**
	 * @return a {@link InputStream} that represents the thumbnail used.
	 * @throws IOException
	 *
	 * @see LibraryResource#getThumbnailInputStream()
	 */
	@Override
	public DLNAThumbnailInputStream getThumbnailInputStream() throws IOException {
		if (MediaDatabase.isAvailable()) {
			Connection connection = null;
			try {
				connection = MediaDatabase.getConnectionIfAvailable();

				if (this.isTVSeries) {
					DLNAThumbnail tvSeriesCover = MediaTableTVSeries.getThumbnailByTitle(connection, this.getDisplayName());
					if (tvSeriesCover != null) {
						return new DLNAThumbnailInputStream(tvSeriesCover);
					}
				}

				if (this.isMovieFolder) {
					DLNAThumbnail movieCover = MediaTableFiles.getThumbnailByTitle(connection, this.getDisplayName());
					if (movieCover != null) {
						return new DLNAThumbnailInputStream(movieCover);
					}
				}
			} finally {
				MediaDatabase.close(connection);
			}
		}

		try {
			return super.getThumbnailInputStream();
		} catch (IOException e) {
			return null;
		}
	}

	@Override
	public boolean isFullyPlayedMark() {
		return isTVSeries &&
		isFullyPlayed(renderer.getAccountUserId());
	}

	private boolean isFullyPlayed(int userId) {
		Connection connection = null;
		try {
			connection = MediaDatabase.getConnectionIfAvailable();
			if (connection != null) {
				return MediaTableTVSeries.isFullyPlayed(connection, name, userId);
			}
		} finally {
			MediaDatabase.close(connection);
		}
		return false;
	}

	@Override
	public String toString() {
		StringBuilder result = new StringBuilder();
		result.append(getClass().getSimpleName());
		result.append(" [id=");
		result.append(getId());
		result.append(", name=");
		result.append(getName());
		result.append(", full path=");
		result.append(getResourceId());
		result.append(", discovered=");
		result.append(isDiscovered());
		result.append(", isTVSeries=");
		result.append(isTVSeries());
		result.append(", isMovieFolder=");
		result.append(isMovieFolder());
		result.append(']');
		return result.toString();
	}
}