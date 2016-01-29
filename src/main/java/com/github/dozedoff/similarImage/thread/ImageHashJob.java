/*  Copyright (C) 2014  Nicholas Wright
    
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
package com.github.dozedoff.similarImage.thread;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;

import javax.imageio.IIOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dozedoff.commonj.hash.ImagePHash;
import com.github.dozedoff.commonj.time.StopWatch;
import com.github.dozedoff.similarImage.db.DBWriter;
import com.github.dozedoff.similarImage.db.ImageRecord;

public class ImageHashJob implements Runnable {
	private static final Logger logger = LoggerFactory.getLogger(ImageHashJob.class);

	private List<Path> work;
	private DBWriter dbWriter;
	private ImagePHash phash;
	private StopWatch sw;

	public ImageHashJob(List<Path> work, DBWriter dbWriter, ImagePHash phash) {
		this.work = work;
		this.dbWriter = dbWriter;
		this.phash = phash;
	}

	@Override
	public void run() {
		if (logger.isDebugEnabled()) {
			sw = new StopWatch();
			sw.start();
		}

		LinkedList<ImageRecord> newRecords = new LinkedList<ImageRecord>();

		for (Path path : work) {
			try {
				long hash = phash.getLongHash(path);
				ImageRecord record = new ImageRecord(path.toString(), hash);
				newRecords.add(record);
			} catch (IIOException iioe) {
				logger.warn("Unable to process image {} - {}", path, iioe.getMessage());
			} catch (IOException e) {
				logger.warn("Could not load file {} - {}", path, e.getMessage());
			} catch (Exception e) {
				logger.warn("Failed to hash image {} - {}", path, e.getMessage());
			}
		}

		if (logger.isDebugEnabled()) {
			sw.stop();
			logger.debug("Took {} to hash {} images", sw.getTime(), work.size());
		}

		dbWriter.add(newRecords);
		logger.debug("{} records added to DBWriter", newRecords.size());
	}
}
