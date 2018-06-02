/*******************************************************************************
 *     SDR Trunk 
 *     Copyright (C) 2014 Dennis Sheirer
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package io.github.dsheirer.source.mixer;

import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.adapter.ISampleAdapter;
import io.github.dsheirer.sample.buffer.ReusableBuffer;
import io.github.dsheirer.sample.buffer.ReusableBufferBroadcaster;
import io.github.dsheirer.sample.buffer.ReusableBufferQueue;
import io.github.dsheirer.sample.real.RealBuffer;
import io.github.dsheirer.source.RealSource;
import io.github.dsheirer.source.SourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class RealMixerSource extends RealSource
{
    private final static Logger mLog = LoggerFactory.getLogger(RealMixerSource.class);
    private ReusableBufferBroadcaster<ReusableBuffer> mBufferBroadcaster = new ReusableBufferBroadcaster<>();
    private ReusableBufferQueue mReusableBufferQueue = new ReusableBufferQueue("RealMixerSource");
    private long mFrequency = 0;
    private int mBufferSize = 16384;

    private BufferReader mBufferReader = new BufferReader();
    private TargetDataLine mTargetDataLine;
    private AudioFormat mAudioFormat;
    private int mBytesPerFrame = 0;
    private ISampleAdapter mSampleAdapter;

    private CopyOnWriteArrayList<Listener<RealBuffer>> mSampleListeners = new CopyOnWriteArrayList<Listener<RealBuffer>>();

    /**
     * Real Mixer Source - constructs a reader on the mixer/sound card target
     * data line using the specified audio format (sample size, sample rate )
     * and broadcasts real buffers (float arrays) to all registered listeners.
     * Reads buffers sized to 10% of the sample rate specified in audio format.
     *
     * @param targetDataLine - mixer or sound card to be used
     * @param format - audio format
     * @param sampleAdapter - adapter to convert byte array data read from the
     * mixer into float array data.  Can optionally invert the channel data if
     * the left/right stereo channels are inverted.
     */
    public RealMixerSource(TargetDataLine targetDataLine,
                           AudioFormat format,
                           ISampleAdapter sampleAdapter)
    {
        mTargetDataLine = targetDataLine;
        mAudioFormat = format;
        mSampleAdapter = sampleAdapter;
    }

	@Override
	public void setSourceEventListener(Listener<SourceEvent> listener)
	{
		//Not implemented
	}

	@Override
	public void removeSourceEventListener()
	{
		//Not implemented
	}

	@Override
	public Listener<SourceEvent> getSourceEventListener()
	{
		//Not implemented
		return null;
	}

    @Override
    public void reset()
    {
    }

    @Override
    public void start()
    {
    }

    @Override
    public void stop()
    {
    }

    public void setListener(Listener<ReusableBuffer> listener)
    {
        mBufferBroadcaster.addListener(listener);

		/* If this is the first listener, start the reader thread */
        if(!mBufferReader.isRunning())
        {
            Thread thread = new Thread(mBufferReader);
            thread.setDaemon(true);
            thread.setName("Sample Reader");
            thread.start();
        }
    }

    public void removeListener(Listener<ReusableBuffer> listener)
    {
        mBufferBroadcaster.removeListener(listener);

		/* If this is the last listener, stop the reader thread */
        if(mSampleListeners.isEmpty())
        {
            mBufferReader.stop();
        }
    }

    public double getSampleRate()
    {
        if(mTargetDataLine != null)
        {
            return mTargetDataLine.getFormat().getSampleRate();
        }
        else
        {
            return 0;
        }
    }

    /**
     * Returns the frequency of this source.  Default is 0.
     */
    public long getFrequency()
    {
        return mFrequency;
    }

    /**
     * Specify the frequency that will be returned from this source.  This may
     * be useful if you are streaming an external audio source in through the
     * sound card and you want to specify a frequency for that source
     */
    public void setFrequency(long frequency)
    {
        mFrequency = frequency;
    }

    /**
     * Reader thread.  Performs blocking read against the mixer target data
     * line, converts the samples to an array of floats using the specified
     * adapter.  Dispatches float arrays to all registered listeners.
     */
    public class BufferReader implements Runnable
    {
        private AtomicBoolean mRunning = new AtomicBoolean();

        @Override
        public void run()
        {
            if(mRunning.compareAndSet(false, true))
            {
                getHeartbeatManager().broadcast();

                if(mTargetDataLine == null)
                {
                    mRunning.set(false);

                    mLog.error("ComplexMixerSource - mixer target data line"
                        + " is null");
                }
                else
                {
                    mBytesPerFrame = mAudioFormat.getSampleSizeInBits() / 8;

                    if(mBytesPerFrame == AudioSystem.NOT_SPECIFIED)
                    {
                        mBytesPerFrame = 2;
                    }
    	    		
    	    		/* Set buffer size to 1/10 second of samples */
                    mBufferSize = (int)(mAudioFormat.getSampleRate()
                        * 0.05) * mBytesPerFrame;

            		/* We'll reuse the same buffer for each read */
                    byte[] buffer = new byte[mBufferSize];

                    try
                    {
                        mTargetDataLine.open(mAudioFormat);

                        mTargetDataLine.start();
                    }
                    catch(LineUnavailableException e)
                    {
                        mLog.error("ComplexMixerSource - mixer target data line"
                            + "not available to read data from", e);

                        mRunning.set(false);
                    }

                    while(mRunning.get())
                    {
                        try
                        {
            				/* Blocking read - waits until the buffer fills */
                            mTargetDataLine.read(buffer, 0, buffer.length);

	                        /* Convert samples to float array */
//TODO: change this sample adapter over to use reusable buffers and move the buffer queue into the adapter
                            float[] samples = mSampleAdapter.convert(buffer);
	            			
	            			/* Dispatch samples to registered listeners */
                            ReusableBuffer reusableBuffer = mReusableBufferQueue.getBuffer(samples.length);
                            reusableBuffer.reloadFrom(samples, System.currentTimeMillis());
                            reusableBuffer.incrementUserCount();
                            mBufferBroadcaster.broadcast(reusableBuffer);
                        }
                        catch(Exception e)
                        {
                            mLog.error("ComplexMixerSource - error while reading"
                                + "from the mixer target data line", e);

                            mRunning.set(false);
                        }
                    }
                }
        		
        		/* Close the data line if it is still open */
                if(mTargetDataLine != null && mTargetDataLine.isOpen())
                {
                    mTargetDataLine.close();
                }
            }
        }

        /**
         * Stops the reader thread
         */
        public void stop()
        {
            if(mRunning.compareAndSet(true, false))
            {
                mTargetDataLine.stop();
                mTargetDataLine.close();
            }
        }

        /**
         * Indicates if the reader thread is running
         */
        public boolean isRunning()
        {
            return mRunning.get();
        }
    }

    @Override
    public void dispose()
    {
        if(mBufferReader != null)
        {
            mBufferReader.stop();
            mSampleListeners.clear();
        }
    }
}
