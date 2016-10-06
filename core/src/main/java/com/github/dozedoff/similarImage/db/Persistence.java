/*  Copyright (C) 2016  Nicholas Wright
    
    This file is part of similarImage - A similar image finder using pHash
    
    similarImage is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.dozedoff.similarImage.db;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dozedoff.similarImage.db.repository.FilterRepository;
import com.github.dozedoff.similarImage.db.repository.RepositoryException;
import com.github.dozedoff.similarImage.db.repository.ormlite.OrmliteFilterRepository;
import com.j256.ormlite.dao.CloseableWrappedIterable;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.dao.LruObjectCache;
import com.j256.ormlite.jdbc.JdbcConnectionSource;
import com.j256.ormlite.stmt.PreparedQuery;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.stmt.SelectArg;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.support.DatabaseConnection;
import com.j256.ormlite.table.TableUtils;

public class Persistence {
	private static final Logger logger = LoggerFactory.getLogger(Persistence.class);
	private final static String defaultDbPath = "similarImage.db";
	private final static String dbPrefix = "jdbc:sqlite:";

	Dao<ImageRecord, String> imageRecordDao;
	Dao<FilterRecord, Integer> filterRecordDao;
	Dao<BadFileRecord, String> badFileRecordDao;
	Dao<IgnoreRecord, Long> ignoreRecordDao;
	Dao<Thumbnail, Integer> thumbnailDao;

	private FilterRepository filterRepository;

	private final ConnectionSource cs;

	PreparedQuery<ImageRecord> filterPrepQuery, distinctPrepQuery;

	SelectArg pathArg = new SelectArg();

	public Persistence() {
		this(defaultDbPath);
	}

	public Persistence(Path dbPath) {
		this(dbPath.toString());
	}

	public Persistence(String dbPath) {
		try {
			String fullDbPath = dbPrefix + dbPath;
			cs = new JdbcConnectionSource(fullDbPath);
			setupDatabase(cs);
			setupDAO(cs);
			createPreparedStatements();
			filterRepository = new OrmliteFilterRepository(filterRecordDao, thumbnailDao);
			logger.info("Loaded database");
		} catch (SQLException e) {
			logger.error("Failed to setup database {}", dbPath, e);
			throw new RuntimeException("Failed to setup database" + dbPath);
		}
	}

	private void createPreparedStatements() throws SQLException {
		QueryBuilder<ImageRecord, String> qb;
		qb = imageRecordDao.queryBuilder();
		filterPrepQuery = qb.where().like("path", pathArg).prepare();
	}

	private void setupDatabase(ConnectionSource cs) throws SQLException {
		logger.info("Setting database config...");
		DatabaseConnection dbConn = cs.getReadWriteConnection();
		dbConn.executeStatement("PRAGMA page_size = 4096;", DatabaseConnection.DEFAULT_RESULT_FLAGS);
		dbConn.executeStatement("PRAGMA cache_size=10000;", DatabaseConnection.DEFAULT_RESULT_FLAGS);
		dbConn.executeStatement("PRAGMA locking_mode=EXCLUSIVE;", DatabaseConnection.DEFAULT_RESULT_FLAGS);
		dbConn.executeStatement("PRAGMA synchronous=NORMAL;", DatabaseConnection.DEFAULT_RESULT_FLAGS);
		dbConn.executeStatement("PRAGMA temp_store = MEMORY;", DatabaseConnection.DEFAULT_RESULT_FLAGS);
		dbConn.executeStatement("PRAGMA journal_mode=MEMORY;", DatabaseConnection.DEFAULT_RESULT_FLAGS);

		logger.info("Setting up database tables...");
		TableUtils.createTableIfNotExists(cs, ImageRecord.class);
		TableUtils.createTableIfNotExists(cs, FilterRecord.class);
		TableUtils.createTableIfNotExists(cs, BadFileRecord.class);
		TableUtils.createTableIfNotExists(cs, IgnoreRecord.class);
		TableUtils.createTableIfNotExists(cs, CustomUserTag.class);
		TableUtils.createTableIfNotExists(cs, Thumbnail.class);
	}

	private void setupDAO(ConnectionSource cs) throws SQLException {
		logger.info("Setting up DAO...");
		imageRecordDao = DaoManager.createDao(cs, ImageRecord.class);
		filterRecordDao = DaoManager.createDao(cs, FilterRecord.class);
		badFileRecordDao = DaoManager.createDao(cs, BadFileRecord.class);
		ignoreRecordDao = DaoManager.createDao(cs, IgnoreRecord.class);
		thumbnailDao = DaoManager.createDao(cs, Thumbnail.class);

		imageRecordDao.setObjectCache(new LruObjectCache(5000));
		filterRecordDao.setObjectCache(new LruObjectCache(1000));
		badFileRecordDao.setObjectCache(new LruObjectCache(1000));
		ignoreRecordDao.setObjectCache(new LruObjectCache(1000));
	}

	public void addRecord(ImageRecord record) throws SQLException {
		imageRecordDao.createIfNotExists(record);
	}

	public void batchAddRecord(final List<ImageRecord> record) throws Exception {
		imageRecordDao.callBatchTasks(new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				for (ImageRecord ir : record) {
					imageRecordDao.createIfNotExists(ir);
				}
				return null;
			}
		});
	}

	public ImageRecord getRecord(Path path) throws SQLException {
		return imageRecordDao.queryForId(path.toString());
	}

	public List<ImageRecord> getRecords(long pHash) throws SQLException {
		ImageRecord searchRecord = new ImageRecord(null, pHash);
		return imageRecordDao.queryForMatching(searchRecord);
	}

	/**
	 * Remove the record from the database.
	 * 
	 * @param record
	 *            to remove
	 * @throws SQLException
	 *             if an error occurs during database operations
	 */
	public void deleteRecord(ImageRecord record) throws SQLException {
		imageRecordDao.delete(record);
	}

	/**
	 * Remove all records in the list from the database.
	 * 
	 * @param records
	 *            to remove
	 * @throws SQLException
	 *             if an error occurs during database operations
	 */
	public void deleteRecord(Collection<ImageRecord> records) {
		for (ImageRecord rec : records) {
			try {
				deleteRecord(rec);
			} catch (SQLException e) {
				logger.warn("Failed to delete record for {}: {}", rec.getPath(), e.toString());
			}
		}
	}

	public boolean isPathRecorded(Path path) throws SQLException {
		String id = path.toString();
		ImageRecord record = imageRecordDao.queryForId(id);

		if (record == null) {
			return false;
		} else {
			return true;
		}
	}

	public boolean isBadFile(Path path) throws SQLException {
		String id = path.toString();
		BadFileRecord record = badFileRecordDao.queryForId(id);

		if (record == null) {
			return false;
		} else {
			return true;
		}
	}

	public CloseableWrappedIterable<ImageRecord> getImageRecordIterator() {
		return imageRecordDao.getWrappedIterable();
	}

	public List<ImageRecord> getAllRecords() throws SQLException {
		return imageRecordDao.queryForAll();
	}

	private void reThrowSQLException(RepositoryException e) throws SQLException {
		throw new SQLException(e.getCause());
	}

	public void addBadFile(BadFileRecord badFile) throws SQLException {
		badFileRecordDao.createOrUpdate(badFile);
	}

	/**
	 * Check if the {@link FilterRecord} with the given hash exists.
	 * 
	 * @param pHash
	 *            to query
	 * @return true if a record is found
	 * @throws SQLException
	 *             in case of a database error
	 * @deprecated This will no longer work if a hash can have multiple tags
	 */
	@Deprecated
	public boolean filterExists(long pHash) throws SQLException {

		List<FilterRecord> filter;
		try {
			filter = filterRepository.getFiltersByHash(pHash);
			return !filter.isEmpty();
		} catch (RepositoryException e) {
			reThrowSQLException(e);
			return true;
		}
	}

	/**
	 * Returns a distinct list of all tags currently in use.
	 * 
	 * @return list of tags
	 */
	public List<String> getFilterTags() {
		List<String> reasons = new LinkedList<String>();

		CloseableWrappedIterable<FilterRecord> iterator = filterRecordDao.getWrappedIterable();

		for (FilterRecord fr : iterator) {
			String reason = fr.getReason();

			if (!reasons.contains(reason)) {
				reasons.add(reason);
			}
		}

		try {
			iterator.close();
		} catch (SQLException e) {
			logger.warn("Failed to close iterator", e);
		}

		return reasons;
	}

	/**
	 * @deprecated Use {@link Persistence#getFilterTags()} instead, better naming.
	 */
	@Deprecated
	public List<String> getFilterReasons() {
		return getFilterTags();
	}

	public List<ImageRecord> filterByPath(Path directory) throws SQLException {
		filterPrepQuery.setArgumentHolderValue(0, directory.toString() + "%");
		return imageRecordDao.query(filterPrepQuery);
	}

	public long distinctHashes() throws SQLException {
		return imageRecordDao.queryRawValue("SELECT COUNT(DISTINCT `pHash`) FROM `imagerecord`");
	}

	public void addIgnore(long pHash) throws SQLException {
		ignoreRecordDao.createOrUpdate(new IgnoreRecord(pHash));
	}

	public boolean isIgnored(long pHash) throws SQLException {
		IgnoreRecord ir = ignoreRecordDao.queryForId(pHash);

		if (ir == null) {
			return false;
		} else {
			return true;
		}
	}

	/**
	 * Get the {@link ConnectionSource} for the database.
	 * 
	 * @return current {@link ConnectionSource}
	 */
	public final ConnectionSource getCs() {
		return cs;
	}
}
