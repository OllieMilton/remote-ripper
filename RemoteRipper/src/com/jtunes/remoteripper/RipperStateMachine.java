package com.jtunes.remoteripper;

import java.io.File;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFileFormat.Type;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.jtunes.util.domain.AudioCd;
import com.jtunes.util.domain.AudioCdTrack;
import com.jtunes.util.domain.RipStatus;

import cdutils.domain.RipProgressEvent;
import cdutils.domain.TOC;
import cdutils.domain.TOCEntry;
import cdutils.exception.DiscReadException;
import cdutils.service.CD;
import cdutils.service.CDDA;
import cdutils.service.RipProgressListener;

public class RipperStateMachine implements RipProgressListener {

	private static RipperStateMachine sm;
	private Log logger = LogFactory.getLog(getClass());
	private RipStatus ripStatus;
	private TOC toc;
	private CD cd;
	private long startTime;
	private RipperState currentState;
	private int trackNo = -1;
	private String trackName;
	private volatile boolean ripError;
	private File file;
	private File ripDir; // TODO configure ripDir
		
	public void start(String device) {
		cd = new CDDA(device);
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
		}
	}
	
	public void abort() {
		ripError = true;
	}
	
	private void makeRipStatus() throws DiscReadException {
		toc = cd.getTableOfContents();
		ripStatus = new RipStatus();
		AudioCd audioCd = new AudioCd();
		audioCd.setDuration(toc.getDuration());
		for (TOCEntry ent : toc.entries()) {
			audioCd.addTrack(new AudioCdTrack(ent.getId(), ent.getDuration()));
		}
		ripStatus.setAudioCd(audioCd);
	}
	
	private void doRip() throws Exception {
		AudioInputStream ais = null;
		try {
			file = new File(ripDir.getAbsolutePath()+"/"+trackName+".wav");
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
	
	private void changeState(RipperState newState) {
		logger.info("Changing state from ["+currentState.getName()+"] to state ["+newState.getName()+"]");
		ripStatus.setTimeElapsed(calculateElapsedTime());
		currentState = newState;
	}
	
	private void reset() {
		if (ripDir != null) {
			if (ripDir.list() != null) {
				for (String s : ripDir.list()) {
					new File(ripDir.getAbsolutePath()+"/"+s).delete();
				}
			}
			ripDir.delete();
		}
		trackNo = -1;
		trackName = null;
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
				sm.ripStatus.setMessage(getName());
				if (sm.trackName != null) {
					sm.changeState(RIP_TRACK);
				}
			}
		},
		RIP_TRACK("Ripping track") {
			
			@Override
			void doAction() {
				try {
					sm.ripStatus.setMessage(getName());
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
				sm.ripStatus.setMessage(getName());
				
			}
		},
		COMPLETE("Complete") {
			
			@Override
			void doAction() {
				sm.reset();
				if (sm.trackNo == sm.toc.entries().size()) {
					sm.cd.eject();
				}
			}
		},
		ERROR("Error") {
			
			@Override
			void doAction() {
				sm.ripStatus.setMessage(getName());
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
	}

	@Override
	public void onError(String message) {
		ripError = true;		
	}
}
