package com.jcope.vnc.server.screen;

import static com.jcope.debug.Debug.assert_;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;

import com.jcope.debug.LLog;
import com.jcope.util.FixedLengthBitSet;
import com.jcope.util.SegmentationInfo;
import com.jcope.vnc.Server.SERVER_PROPERTIES;
import com.jcope.vnc.server.ClientHandler;
import com.jcope.vnc.server.DirectRobot;
import com.jcope.vnc.server.StateMachine;
import com.jcope.vnc.shared.StateMachine.SERVER_EVENT;

/**
 * 
 * @author Joseph Copenhaver
 * 
 * This class will contain registered listener interfaces that
 * get notified when a particular screen changes graphically.
 * 
 * For each ScreenMonitor there is exactly one screen device
 * being sampled for which there can be one or more listening
 * components.
 * 
 * Components will be notified when a segment of a screen changes.
 * It is then the responsibility of the listening component to fetch
 * from this class the data segment of interest.
 * 
 * Should all listeners be terminated, then the Screen Monitor shall
 * terminate and be ready for garbage collection.
 * 
 * Let each screen be broken up into segmentWidth by segmentHeight pixel segments.
 * 
 * Let each segment be assigned an ID from left to right, top down where the first tile is ID 0
 * Let segment ID -1 indicate the collection of segments as a whole (The entire screen)
 *
 */

public class Monitor extends Thread
{
	public static final long NO_LISTENER_MS = 5000; // dummy value to allow things to settle into nop state
	private static final boolean OBEY_SPEED_LIMITS = (Boolean) SERVER_PROPERTIES.OBEY_SPEED_LIMITS.getValue();
    private static final long MIN_REFRESH_MS = (Long) SERVER_PROPERTIES.MIN_MONITOR_SCANNING_PERIOD.getValue();
    int screenX, screenY;
    SegmentationInfo segInfo = new SegmentationInfo();
    private Integer screenWidth = null, screenHeight;
    private ArrayList<ClientHandler> clients;
    private DirectRobot dirbot;
    private int[][] segments;
    private Integer[] solidSegments;
    private FixedLengthBitSet changedSegments;
    private volatile boolean stopped = Boolean.FALSE;
    private volatile boolean joined = Boolean.FALSE;
    private Boolean mouseOnMyScreen = null;
    private final Point mouseLocation = new Point();
    
    private Semaphore limitLock = new Semaphore(1, true);
    private TreeSet<Long> limitTreeSet = new TreeSet<Long>();
    private volatile long refreshMS;
    
    public Monitor(int segmentWidth, int segmentHeight, DirectRobot dirbot, ArrayList<ClientHandler> clients)
    {
        super(String.format("Monitor: %s", dirbot.toString()));
        segInfo.segmentWidth = segmentWidth;
        segInfo.segmentHeight = segmentHeight;
        this.dirbot = dirbot;
        this.clients = clients;
        syncBounds();
    }
    
    private void syncBounds()
    {
        Integer lastWidth = screenWidth;
        Integer lastHeight = screenHeight;
        Rectangle bounds = getScreenBounds();
        screenX = bounds.x;
        screenY = bounds.y;
        screenWidth = bounds.width;
        screenHeight = bounds.height;
        if (lastWidth == null || lastWidth != screenWidth || lastHeight != screenHeight)
        {
            segInfo.loadConfig(screenWidth, screenHeight, segInfo.segmentWidth, segInfo.segmentHeight);
            segments = new int[segInfo.numSegments][];
            solidSegments = new Integer[segInfo.numSegments];
            changedSegments = new FixedLengthBitSet(segInfo.numSegments);
            for (int i=0; i<segInfo.numSegments; i++)
            {
                segments[i] = new int[getSegmentPixelCount(i)];
            }
            if (lastWidth != null)
            {
                // TODO: provide ability to lock a set of clients
                StateMachine.handleServerEvent(clients, SERVER_EVENT.SCREEN_RESIZED, screenWidth, screenHeight);
            }
        }
    }
    
    private void syncMouse()
    {
        Boolean mouseWasOnMyScreen = mouseOnMyScreen;
        int lastX = mouseLocation.x, lastY = mouseLocation.y;
        mouseOnMyScreen = (DirectRobot.getMouseInfo(mouseLocation) == dirbot.device);
        if (mouseOnMyScreen)
        {
            if (mouseWasOnMyScreen == null || lastX != mouseLocation.x || lastY != mouseLocation.y)
            {
                StateMachine.handleServerEvent(clients, SERVER_EVENT.CURSOR_MOVE, Integer.valueOf(mouseLocation.x), Integer.valueOf(mouseLocation.y));
            }
        }
        else if (mouseWasOnMyScreen != null && mouseWasOnMyScreen)
        {
            StateMachine.handleServerEvent(clients, SERVER_EVENT.CURSOR_GONE);
        }
    }
    
    public void run()
    {
        // detect change in a segment of the configured screen
        // notify all listeners of the changed segment
        
        boolean changed;
        
        int[] buffer = new int[segInfo.maxSegmentNumPixels];
        Integer[] solidSegmentRef = new Integer[]{null};
        int[] segmentDim = new int[2];
        int x, y;
        long startAt, timeConsumed;
        ArrayList<ClientHandler> newClients = new ArrayList<ClientHandler>();
        
        startAt = 0;
        
        try
        {
            while (!stopped)
            {
            	if (OBEY_SPEED_LIMITS)
            	{
            		startAt = System.currentTimeMillis();
            	}
                
                syncMouse();
                
                changed = Boolean.FALSE;
                
                dirbot.markRGBCacheDirty();
                
                for (int i=0; i<=segInfo.maxSegmentID; i++)
                {
                    getSegmentPos(i, segmentDim);
                    x = segmentDim[0];
                    y = segmentDim[1];
                    getSegmentDim(i, segmentDim);
                    dirbot.getRGBPixels(x, y, segmentDim[0], segmentDim[1], buffer);
                    if (copyIntArray(segments[i], buffer, segments[i].length, solidSegmentRef))
                    {
                        changed = Boolean.TRUE;
                        changedSegments.set(i, Boolean.TRUE);
                    }
                    solidSegments[i] = solidSegmentRef[0];
                }
                
                for (ClientHandler client : clients)
                {
                    if (client.getIsNewFlag())
                    {
                        newClients.add(client);
                    }
                }
                
                if (changed)
                {
                    FixedLengthBitSet tmp = changedSegments.clone();
                    for (ClientHandler client : clients)
                    {
                        if (client.getIsNewFlag())
                        {
                            continue;
                        }
                        ScreenListener l = client.getScreenListener(dirbot);
                        l.onScreenChange(tmp);
                    }
                    changedSegments.fill(Boolean.FALSE);
                }
                
                if (newClients.size() > 0)
                {
                    FixedLengthBitSet tmp = new FixedLengthBitSet(changedSegments.length, Boolean.TRUE);
                    for (ClientHandler client : newClients)
                    {
                        client.setIsNewFlag(Boolean.FALSE);
                        ScreenListener l = client.getScreenListener(dirbot);
                        l.onScreenChange(tmp);
                    }
                    newClients.clear();
                }
                
                if (OBEY_SPEED_LIMITS)
                {
	                timeConsumed = System.currentTimeMillis() - startAt;
	                
	                long l_refreshMS = refreshMS;
	                
	                if (timeConsumed < l_refreshMS)
	                {
	                    try
	                    {
	                        sleep(l_refreshMS - timeConsumed);
	                    }
	                    catch (InterruptedException e)
	                    {
	                        LLog.e(e);
	                    }
	                }
                }
            }
        }
        finally {
            stopped = Boolean.TRUE;
            joined = Boolean.TRUE;
        }
    }
    
    public void sendDisplayInitEvents(ClientHandler client)
    {
        Rectangle bounds = getScreenBounds();
        client.sendEvent(SERVER_EVENT.SCREEN_RESIZED, bounds.width, bounds.height);
        client.sendEvent(SERVER_EVENT.SCREEN_SEGMENT_SIZE_UPDATE, segInfo.segmentWidth, segInfo.segmentHeight);
        if (mouseOnMyScreen != null && mouseOnMyScreen)
        {
            client.sendEvent(SERVER_EVENT.CURSOR_MOVE, Integer.valueOf(mouseLocation.x), Integer.valueOf(mouseLocation.y));
        }
    }
    
    /**
     * Eratta: src array content MAY CHANGE as a result of this function!
     * It is faster to fill a buffer with a SOLID color and compare arrays
     * using a syscall than it is to iterate over the array and compare
     * individual elements with a sample color.
     * 
     * Note that this can only occur when the src and dst buffers are length
     * aligned and the length parameter is aligned with them as well.
     * 
     * @param dst
     * @param src
     * @param length
     * @param solidColorOut
     * @return
     */
    private boolean copyIntArray(int[] dst, int[] src, int length, Integer[] solidColorOut)
    {
        boolean rval = Boolean.FALSE;
        int solidColor, srcPixel;
        
        if (solidColorOut != null && solidColorOut.length > 0 && length > 0)
        {
            solidColor = src[0];
        }
        else
        {
            solidColor = 0;
            if (solidColorOut.length > 0)
            {
                solidColorOut[0] = null;
            }
            solidColorOut = null;
        }
        
        if (src.length == length && dst.length == length)
        {
            rval = !Arrays.equals(src, dst);
            System.arraycopy(src, 0, dst, 0, length);
            if (solidColorOut != null)
            {
                Arrays.fill(src, solidColor);
                if (!Arrays.equals(src, dst))
                {
                    solidColorOut[0] = null;
                    solidColorOut = null;
                }
            }
        }
        else
        {
            // this appears to be faster than doing multiple loops
            // or even separate continual loops
            // java seems to compile this into efficient piecewise code...
            for (int i=0;i<length; i++)
            {
                srcPixel = src[i];
                if (dst[i] != srcPixel)
                {
                    dst[i] = srcPixel;
                    rval = Boolean.TRUE;
                }
                if (solidColorOut != null && srcPixel != solidColor)
                {
                    solidColorOut[0] = null;
                    solidColorOut = null;
                }
                if (solidColorOut == null && rval && i<length-1)
                {
                    i++;
                    System.arraycopy(src, i, dst, i, length-i);
                }
            }
        }
        
        if (solidColorOut != null)
        {
            solidColorOut[0] = solidColor;
        }
        
        return rval;
    }
    
    public int getSegmentID(int x, int y)
    {
        int rval = segInfo.getSegmentID(x, y);
        
        return rval;
    }
    
    public void getSegmentDim(int segmentID, int[] dim)
    {
        segInfo.getDim(segmentID, dim);
    }
    
    public void getSegmentPos(int segmentID, int[] absPos)
    {
        segInfo.getPos(segmentID, absPos);
    }
    
    public void getSegmentIdxPos(int segmentID, int[] pos)
    {
        segInfo.getIdxPos(segmentID, pos);
    }
    
    public int getSegmentPixelCount(int segmentID)
    {
        int rval = segInfo.getSegmentPixelCount(segmentID);
        
        return rval;
    }
    
    public int getMaxSegmentPixelCount()
    {
        return segInfo.maxSegmentNumPixels;
    }
    
    public int getSegmentWidth()
    {
        return segInfo.segmentWidth;
    }
    
    public int getSegmentHeight()
    {
        return segInfo.segmentHeight;
    }
    
    public Rectangle getScreenBounds()
    {
        return dirbot.getScreenBounds();
    }
    
    public int getSegmentCount()
    {
        return segInfo.numSegments;
    }
    
    private void signalStop()
    {
        stopped = true;
    }
    
    public boolean isRunning()
    {
        return !stopped;
    }
    
    public boolean isJoined()
    {
        return joined;
    }
    
    public void kill()
    {
        signalStop();
    }

    public int[] getSegment(int segmentID)
    {
        int[] rval = (segmentID == -1) ? dirbot.getRGBPixels() : segments[segmentID];
        
        return rval;
    }

    public Integer getSegmentSolidColor(int segmentID)
    {
        Integer rval;
        
        if (segmentID == -1)
        {
            rval = null;
        }
        else
        {
            rval = solidSegments[segmentID];
        }
        
        return rval;
    }
    
    public Object getSegmentOptimized(int segmentID)
    {
        Object rval = getSegmentSolidColor(segmentID);
        
        if (rval == null)
        {
            rval = getSegment(segmentID);
        }
        
        return rval;
    }

    public void getOrigin(int[] pos)
    {
        assert_(pos != null);
        assert_(pos.length > 1);
        
        pos[0] = screenX;
        pos[1] = screenY;
    }

	public void throttle(boolean addPeriod, Long periodMS)
	{
		assert_(!addPeriod || periodMS != null);
		if (periodMS == null)
		{
			// refresh rate NOT affected by this value/config
			return;
		}
		if (periodMS < MIN_REFRESH_MS)
		{
			periodMS = MIN_REFRESH_MS;
		}
		try {
			limitLock.acquire();
		} catch (InterruptedException e) {
			LLog.e(e);
		}
		try
		{
			synchronized(limitTreeSet)
			{
				if (addPeriod)
				{
					limitTreeSet.add(periodMS);
					refreshMS = limitTreeSet.ceiling(0L);
					return;
				}
				else if (limitTreeSet.remove(periodMS))
				{
					if (limitTreeSet.isEmpty())
					{
						refreshMS = NO_LISTENER_MS;
						return;
					}
					refreshMS = limitTreeSet.ceiling(0L);
					return;
				}
				assert_(false);
			}
		}
		finally {
			limitLock.release();
		}
	}

}
