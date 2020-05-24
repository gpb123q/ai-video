/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.restcomm.media.resource.dtmf;

import org.restcomm.media.ComponentType;
import org.restcomm.media.component.AbstractSource;
import org.restcomm.media.component.audio.AudioInput;
import org.restcomm.media.component.oob.OOBInput;
import org.restcomm.media.scheduler.PriorityQueueScheduler;
import org.restcomm.media.spi.dtmf.DtmfGenerator;
import org.restcomm.media.spi.dtmf.DtmfGeneratorEvent;
import org.restcomm.media.spi.dtmf.DtmfGeneratorListener;
import org.restcomm.media.spi.format.AudioFormat;
import org.restcomm.media.spi.format.FormatFactory;
import org.restcomm.media.spi.format.Formats;
import org.restcomm.media.spi.listener.Listeners;
import org.restcomm.media.spi.listener.TooManyListenersException;
import org.restcomm.media.spi.memory.Frame;
import org.restcomm.media.spi.memory.Memory;
import org.restcomm.media.spi.pooling.PooledObject;

/**
 * InbandGenerator generates Inband DTMF Tone only for uncompressed LINEAR
 * codec. After creating instance of InbandGenerator, it needs to be initialized
 * so that all the Tones are generated and kept ready for transmission once
 * start is called.
 * 
 * By default the Tone duration is 80ms. This is suited for Tone Detector who
 * has Tone duration of greater than 40 and less than 80ms. For Tone Detector
 * who's Tone duration is set greater than 80ms may fail to detect Tone
 * generated by InbandGenerator(with duration 80ms). In that case increase the
 * duration here too.
 * 
 * @author yulian oifa
 * @author amit bhayani
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 */
public class GeneratorImpl extends AbstractSource implements DtmfGenerator, PooledObject {

    private final static AudioFormat linear = FormatFactory.createAudioFormat("linear", 8000, 16, 1);
    private long period = 20000000L;
    private int packetSize = (int)(period / 1000000) * linear.getSampleRate()/1000 * linear.getSampleSize() / 8;    

    private final static Formats formats = new Formats();
    static {
        formats.add(linear);
    }
    
    public final static String[][] events = new String[][]{
        {"1", "2", "3", "A"},
        {"4", "5", "6", "B"},
        {"7", "8", "9", "C"},
        {"*", "0", "#", "D"}
    };
    private int[] lowFreq = new int[]{697, 770, 852, 941};
    private int[] highFreq = new int[]{1209, 1336, 1477, 1633};
    private String digit = null;    // Min duration = 40ms and max = 500ms
    private String oobDigit = null;
    private int oobDigitValue=-1;
    
    private int toneDuration = 50;
    private short A = Short.MAX_VALUE / 2;
    private int volume = 0;
    private int f1,  f2;
    private double dt;
    private int pSize;
    private double time = 0;

    private AudioInput input;
    private OOBInput oobInput;
    private OOBGenerator oobGenerator;
    
    private final Listeners<DtmfGeneratorListener> listeners;
    DtmfGeneratorEvent event=new DtmfGeneratorEvent(GeneratorImpl.this,DtmfGeneratorEvent.COMPLETED);
    
    public GeneratorImpl(String name, PriorityQueueScheduler scheduler) {
        super(name, scheduler,scheduler.INPUT_QUEUE);
        dt = 1.0 / linear.getSampleRate();
        
        this.input=new AudioInput(ComponentType.DTMF_GENERATOR.getType(),packetSize);
        this.connect(this.input);
        
        this.oobInput=new OOBInput(ComponentType.DTMF_GENERATOR.getType());
        this.oobGenerator=new OOBGenerator(scheduler,oobInput); 
        this.listeners = new Listeners<DtmfGeneratorListener>();        
    }

    public void addListener(final DtmfGeneratorListener listener) 
    {
        try 
        {
          listeners.add(listener);
        } 
        catch(final TooManyListenersException ignored) 
        {
          // This exception is never thrown by Listeners.add();
        }
    }

    public void removeListener(final DtmfGeneratorListener listener) 
    {
        listeners.remove(listener);
    }

    public void clearAllListeners() 
    {
        listeners.clear();
    }
      
    public AudioInput getAudioInput()
    {
        return this.input;
    }
    
    public OOBInput getOOBInput()
    {
        return this.oobInput;
    }    
    
    @Override
    public void activate() {
        if(oobDigit!=null) {
            oobGenerator.index=0;
            oobGenerator.activate();            
        }
        
        if (digit != null) {
            time = 0;
            start();
        }     
    }
    
    public void setOOBDigit(String digit) {
        if(digit.charAt(0)>='0' && digit.charAt(0)<='9')                
            oobDigitValue=(digit.charAt(0)-'0');
        else if(digit.charAt(0)=='*')
            oobDigitValue=10;
        else if(digit.charAt(0)=='#')
            oobDigitValue=11;
        else if(digit.charAt(0)>='A' && digit.charAt(0)<='D')
            oobDigitValue=12+digit.charAt(0)-'A';
        else if(digit.charAt(0)>='a' && digit.charAt(0)<='d')
            oobDigitValue=12+digit.charAt(0)-'a';
        else
            return;   
        
        oobGenerator.index=0;
        this.oobDigit=digit;
        this.digit=null;
    }
    
    public void setDigit(String digit) {
        this.oobDigit=null;
        this.digit = digit;
        this.time=0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (events[i][j].equalsIgnoreCase(digit)) {
                    f1 = lowFreq[i];
                    f2 = highFreq[j];
                }
            }
        }
    }

    @Override 
    public void completed() 
    {
        super.completed();
        listeners.dispatch(event);
    }
    
    public String getDigit() {
        return this.digit;
    }

    public String getOOBDigit() {
        return this.oobDigit;
    }
    
    public void setToneDuration(int duration) {
        if (duration < 40) {
            throw new IllegalArgumentException("Duration cannot be less than 40ms");
        }
        this.toneDuration = duration;
    }

    public int getToneDuration() {
        return toneDuration;
    }

    public int getVolume() {
        return this.volume;
    }

    public void setVolume(int volume) {
        if (volume > 0) {
            throw new IllegalArgumentException("Volume has to be negative value expressed in dBm0");
        }
        this.volume = volume;
        A = (short) (Math.pow(Math.pow(10, volume), 0.1) * (Short.MAX_VALUE / 2));
    }

    private short getValue(double t) {
        return (short) (A * (Math.sin(2 * Math.PI * f1 * t) + Math.sin(2 * Math.PI * f2 * t)));
    }

    public Formats getNativeFormats() {
        return formats;
    }

    @Override
    public Frame evolve(long timestamp) {
        if(time > (double) toneDuration / 1000.0)
            return null;
        
        int k = 0;
        int frameSize = (int) ((double) 20 / 1000.0 / dt);
        Frame frame = Memory.allocate(2* frameSize);
        byte[] data = frame.getData();
        for (int i = 0; i < frameSize; i++) {
            short v = getValue(time + dt * i);
            data[k++] = (byte) v;
            data[k++] = (byte) (v >> 8);
        }
        frame.setOffset(0);
        frame.setLength(2* frameSize);
        frame.setTimestamp(getMediaTime());
        frame.setDuration(20000000L);

        time += ((double) 20) / 1000.0;
        if(time >= (double)toneDuration / 1000.0) 
            listeners.dispatch(event);
        
        return frame;
    }

    @Override
    public void deactivate() {
        stop();
        oobGenerator.deactivate();
    } 
    
    @Override
    public void wakeup() {
        if(this.oobDigit!=null)
            oobGenerator.wakeup();
        else if(this.digit!=null)
            super.wakeup();
    }
    
    private class OOBGenerator extends AbstractSource {
        int index=0;
        int eventDuration=0;
        int oobVolume;
        public OOBGenerator(PriorityQueueScheduler scheduler,OOBInput input) {
            super("oob generator", scheduler,scheduler.INPUT_QUEUE);
            this.connect(input);
        }
        
        @Override
        public Frame evolve(long timestamp) {
            if(index > ((toneDuration / 20)+2))
                return null;                        
            
            Frame frame = Memory.allocate(4);
            byte[] data=frame.getData();
            
            data[0]=(byte)oobDigitValue;
            
            oobVolume=0-volume;
            if(index > (toneDuration / 20))
                //with end of event flag
                data[1]=(byte)(0xBF & oobVolume);   
            else
                //without end of event flag
                data[1]=(byte)(0x3F & oobVolume);
            
            eventDuration=(short)(160*index);
            data[2]=(byte)((eventDuration>>8) & 0xFF);
            data[3]=(byte)(eventDuration & 0xFF);
            
            frame.setOffset(0);
            frame.setLength(4);
            frame.setTimestamp(getMediaTime());
            frame.setDuration(20000000L);
            
            index++;
            if(index == ((toneDuration / 20) + 2)) 
                listeners.dispatch(event);
        
            return frame;
        }
        
        @Override
        public void activate() {
            start();
        }
        
        @Override
        public void deactivate() {
            stop();
        } 
    }

    @Override
    public void checkIn() {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void checkOut() {
        // TODO Auto-generated method stub
        
    }
}