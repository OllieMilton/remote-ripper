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
import cdutils.service.CDDA;
import cdutils.service.RipProgressListener;
import cdutils.service.StubCDDA;
import ollie.utils.state.StateHolder;

class RipperStateMachine implements RipProgressListener {

	private static RipperStateMachine sm;
	private Log logger = LogFactory.getLog(getClass());
	private RipStatus ripStatus;
	private TOC toc;
	private CD cd;
	private long startTime;
	private int trackNo = -1;
	private String trackName;
	private volatile boolean ripError;
	private volatile boolean wait;
	private File file;
	private File ripDir;
	private Runnable sendStatus;
	private String systemId;
	private StateHolder<RipperState> currentState;
	private String sessionId;
	private String ripUploadAddress;
	
	RipperStateMachine(Runnable sendStatus, String sessionId, String systemId) {
		this.sendStatus = sendStatus;
		this.sessionId = sessionId;
		this.systemId = systemId;
		currentState = new StateHolder<RipperState>(RipperState.READ_DISC);
	}
	
	void start(String device, String ripTmpDir, String ripUploadAddress) {
		ripDir = new File(ripTmpDir);
		ripDir.mkdirs();
		if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
			cd = new StubCDDA();	
		} else {
			cd = new CDDA(device);
		}
		this.ripUploadAddress = ripUploadAddress;
		sm = this;
	}
		
	RipStatus getStatus() {
		return ripStatus;
	}
		
	void tick() {
		currentState.get().doAction();
	}
	
	void ripTrack(int track, String name) {
		if (currentState.get() == RipperState.WAIT_FOR_START || currentState.get() == RipperState.COMPLETE) {
			trackNo = track;
			trackName = name;
			ripStatus.setRipTrack(trackNo);
			changeState(RipperState.RIP_TRACK);
		}
	}
	
	void startUpload() {
		if (currentState.get() == RipperState.RIP_TRACK_DONE) {
			changeState(RipperState.UPLOAD);
		}
	}
	
	void cancel() {
		if (cd != null) {
			cd.eject();
		}
	}
	
	void finalise() {
		if (currentState.get() == RipperState.COMPLETE) {
			changeState(RipperState.FINALISE);
		}
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
		logger.info("Changing state from ["+currentState.get().getName()+"] to state ["+newState.getName()+"]");
		ripStatus.setTimeElapsed(calculateElapsedTime());
		currentState.transition(newState);
		ripStatus.setMessage(currentState.get().name);
		ripStatus.setState(currentState.get().toString());
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
		ripError = false;
		ripStatus.setToc(null);
		ripStatus.setTimeElapsed(null);
		resetForNextTrack();		
	}
	
	private void resetForNextTrack() {
		ripStatus.setRipTrack(-1);
		ripStatus.setProgress(0);
		trackNo = -1;
		trackName = null;
		wait = false;
		sendStatus.run();
	}
	
	private void doUpload() throws IOException {
		HttpClient client = HttpClientBuilder.create().build();
		HttpPost post = new HttpPost(ripUploadAddress+"?sessionId="+sessionId+"&systemId="+systemId+"&fileSize="+file.length()+"&fileName="+trackName+".wav");
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
				if (!sm.cd.isDiscInDrive()) {
					sm.changeState(ERROR);
				}
			}
		},
		RIP_TRACK("Ripping track") {
			
			@Override
			void doAction() {
				try {
					sm.doRip();
					if (sm.ripError) {
						sm.changeState(ERROR);
					} else {
						sm.changeState(RIP_TRACK_DONE);
					}
				} catch (Exception e) {
					sm.logger.error("An error occurred while ripping track ["+sm.trackNo+"]",e);
					sm.changeState(ERROR);
				} 
			}
		},
		RIP_TRACK_DONE("Rip done") {
			@Override
			void doAction() {
				if (!sm.cd.isDiscInDrive()) {
					sm.changeState(ERROR);
				}
			}
		},
		UPLOAD("Upload") {
			
			@Override
			void doAction() {
				try {
					if (sm.cd.isDiscInDrive()) {
						sm.doUpload();
						sm.changeState(COMPLETE);
					} else {
						sm.changeState(ERROR);
					}
				} catch (Exception e) {
					sm.logger.error("An error occurred while uploading track ["+sm.trackNo+"]",e);
					sm.changeState(ERROR);
				}
			}
		},
		COMPLETE("Complete") {
			
			@Override
			void doAction() {
				if (sm.cd.isDiscInDrive()) {
					if (!sm.wait) {
						sm.resetForNextTrack();
						sm.wait = true;
					}
				} else {
					sm.changeState(ERROR);
				}
			}
		},
		FINALISE("Finalise") {
			@Override
			void doAction() {
				if (sm.cd.eject()) {
					sm.reset();
					sm.changeState(READ_DISC);
				} else {
					sm.changeState(ERROR);
				}
			}
		},
		ERROR("Error") {
			
			@Override
			void doAction() {
				sm.cd.eject();
				sm.reset();
				sm.changeState(READ_DISC);
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
