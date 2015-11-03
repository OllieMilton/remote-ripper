package com.jtunes.remoteripper;

import java.io.File;
import java.io.IOException;

import javax.sound.sampled.AudioFileFormat.Type;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.client.HttpClientBuilder;

import com.jtunes.util.domain.RipStatus;

import cdutils.domain.RipProgressEvent;
import cdutils.domain.TOC;
import cdutils.exception.DiscReadException;
import cdutils.service.CD;
import cdutils.service.RipProgressListener;
import cdutils.service.StubCDDA;

public class RipperStateMachine implements RipProgressListener {

	private static RipperStateMachine sm;
	private Log logger = LogFactory.getLog(getClass());
	private RipStatus ripStatus;
	private TOC toc;
	private CD cd;
	private long startTime;
	private volatile RipperState currentState = RipperState.READ_DISC;
	private int trackNo = -1;
	private String trackName;
	private volatile boolean ripError;
	private volatile boolean wait;
	private File file;
	private File ripDir = new File("C:/music/rip/"); // TODO configure ripDir
	private Runnable sendStatus;
	private String systemId;
	
	RipperStateMachine(Runnable sendStatus, String systemId) {
		this.sendStatus = sendStatus;
		this.systemId = systemId;
	}
	
	public void start(String device) {
		cd = new StubCDDA();
		sm = this;
	}
	
	public RipStatus getStatus() {
		return ripStatus;
	}
		
	public void tick() {
		if (currentState != null) {
			currentState.doAction();
		}
	}
	
	public void ripTrack(int track, String name) {
		if (currentState == RipperState.WAIT_FOR_START) {
			trackNo = track;
			trackName = name;
		} else if (currentState == RipperState.COMPLETE) {
			trackNo = track;
			trackName = name;
			changeState(RipperState.WAIT_FOR_START);
		}
	}
	
	public void abort() {
		ripError = true;
	}
	
	private void makeRipStatus() throws DiscReadException {
		toc = cd.getTableOfContents();
		ripStatus = new RipStatus();
		toc.setMusicbrainzDiscURL(cd.getMusicBrainzURL());
		toc.setMusicbrainzDiscId(cd.getMusicBrainzDiscId());
		toc.setCddbId(cd.getCDDBId());
		ripStatus.setToc(toc);
	}
	
	private void doRip() throws Exception {
		AudioInputStream ais = null;
		try {
			file = new File(ripDir, "/"+trackName+".wav");
			logger.info("Starting rip on track ["+trackNo+"]");
			ais = cd.getTrack(trackNo, this);
			AudioSystem.write(ais, Type.WAVE, file);
		} catch (Exception e) {
			cd.cancel();
			throw e;
		} finally {
			if (ais != null) {
				ais.close();
			}
		}
	}
	
	private String calculateElapsedTime() {
		int total = (int) ((System.currentTimeMillis()-startTime)/1000);
		return String.format("%02d:%02d", (total/60), (total%60));
	}
	
	private synchronized void changeState(RipperState newState) {
		logger.info("Changing state from ["+currentState.getName()+"] to state ["+newState.getName()+"]");
		ripStatus.setTimeElapsed(calculateElapsedTime());
		currentState = newState;
		ripStatus.setMessage(currentState.name);
		ripStatus.setState(currentState.toString());
		sendStatus.run();
	}
	
	private void reset() {
		if (ripDir != null) {
			if (ripDir.list() != null) {
				for (String s : ripDir.list()) {
					new File(ripDir.getAbsolutePath()+"/"+s).delete();
				}
			}
		}
		trackNo = -1;
		trackName = null;
		wait = false;
		ripError = false;
	}
	
	private void doUpload() throws IOException {
		HttpClient client = HttpClientBuilder.create().build();
		HttpPost post = new HttpPost("http://localhost:8888/jTunes/server/upload?systemId="+systemId+"&fileSize="+file.length()+"&fileName="+trackName+".wav");

		HttpEntity entity = new FileEntity(file);
		
		post.setEntity(entity);
		HttpResponse resp = client.execute(post);

		int response = resp.getStatusLine().getStatusCode();
		if (response != 200) {
			throw new IOException("Received error ["+response+"] from server.");
		}
	}
	
	private enum RipperState {
		
		READ_DISC("Reading disc") {

			@Override
			void doAction() {
				try {
					sm.startTime = System.currentTimeMillis();
					sm.makeRipStatus();
					sm.changeState(WAIT_FOR_START);
				} catch (DiscReadException e) {
					sm.ripStatus = new RipStatus();
				}				
			}
		},
		WAIT_FOR_START("Wait for start") {
			
			@Override
			void doAction() {
				if (sm.trackName != null) {
					sm.wait = false;
					sm.changeState(RIP_TRACK);
				}
			}
		},
		RIP_TRACK("Ripping track") {
			
			@Override
			void doAction() {
				try {
					sm.ripStatus.setRipTrack(sm.trackNo);
					sm.ripStatus.setProgress(0);
					sm.doRip();
					if (sm.ripError) {
						sm.changeState(ERROR);
					} else {
						sm.changeState(UPLOAD);
					}
				} catch (Exception e) {
					sm.logger.error("An error occurred while ripping track ["+sm.trackNo+"]",e);
					sm.changeState(ERROR);
				} 
				
			}
		},
		UPLOAD("Upload") {
			
			@Override
			void doAction() {
				try {
					sm.doUpload();
					sm.changeState(COMPLETE);
				} catch (Exception e) {
					sm.logger.error("An error occurred while uploading track ["+sm.trackNo+"]",e);
					sm.changeState(ERROR);
				}
			}
		},
		COMPLETE("Complete") {
			
			@Override
			void doAction() {
				if (!sm.wait) {
					sm.reset();
					if (sm.trackNo == sm.toc.entries().size()) {
						sm.cd.eject();
						sm.changeState(READ_DISC);
					}
					sm.wait = true;
				}
			}
		},
		ERROR("Error") {
			
			@Override
			void doAction() {
				sm.cd.eject();
				sm.reset();
			}
		};
		
		abstract void doAction();

		private String name;
		RipperState(String name) {
			this.name = name;
		}

		String getName() {
			return name;
		}
	}
	
	@Override
	public void onRipProgressEvent(RipProgressEvent event) {
		ripStatus.setProgress(event.getProgress());
		ripStatus.setTimeElapsed(calculateElapsedTime());
		sendStatus.run();
	}

	@Override
	public void onError(String message) {
		ripError = true;		
	}
}
