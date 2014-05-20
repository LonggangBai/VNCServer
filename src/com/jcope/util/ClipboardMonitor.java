package com.jcope.util;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.Semaphore;

import javax.imageio.ImageIO;

import com.jcope.debug.LLog;

public class ClipboardMonitor extends Thread implements ClipboardOwner
{
    public static interface ClipboardListener
    {
        public void onChange(Clipboard clipboard);
    }
    
    public static volatile boolean hasInstance = Boolean.FALSE;
    private static final long delay_ms = 200L;
    private static final long mac_observer_ms = 400L;
    private static Clipboard clipboard;  
    private static ArrayList<ClipboardListener> listeners;
    private static final ClipboardMonitor[] selfRef = new ClipboardMonitor[]{null};
    private static final Semaphore instanceSema = new Semaphore(1, Boolean.TRUE);
    
    private volatile boolean disposed;
    private Semaphore idleSema;
    private Semaphore notificationSema;
    private volatile boolean changed;
    private Thread macClipboardChangeObserver;
    private Runnable mac_syncObserverCacheCallback;
    
    private ClipboardMonitor()
    {
        // static
        hasInstance = Boolean.TRUE;
        clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        listeners = new ArrayList<ClipboardListener>(1);
        
        // instance member initialization
        disposed = Boolean.FALSE;
        idleSema = new Semaphore(1, Boolean.TRUE);
        notificationSema = new Semaphore(0, Boolean.TRUE);
        changed = Boolean.FALSE;
        
        if (Platform.isMac())
        {
            final Runnable[] syncObserverCacheCallbackRef = new Runnable[]{null};
            macClipboardChangeObserver = new Thread() {
                
                {
                    syncObserverCacheCallbackRef[0] = new Runnable() {

                        @Override
                        public void run()
                        {
                            try
                            {
                                cacheSema.acquire();
                            }
                            catch (InterruptedException e)
                            {
                                LLog.e(e);
                            }
                            try
                            {
                                cacheSupportedData();
                            }
                            catch (UnsupportedFlavorException e)
                            {
                                LLog.e(e, Boolean.FALSE);
                            }
                            catch (IOException e)
                            {
                                LLog.e(e, Boolean.FALSE);
                            }
                            finally {
                                cacheSema.release();
                            }
                        }
                        
                    };
                }
                
                private final Semaphore cacheSema = new Semaphore(1, Boolean.TRUE);
                private HashMap<DataFlavor, Object> cache = new HashMap<DataFlavor, Object>();
                
                private Object getComparableData(DataFlavor flavor) throws UnsupportedFlavorException, IOException
                {
                    Object rval = clipboard.getData(flavor);
                    
                    if (null != rval)
                    {
                        if (flavor.equals(DataFlavor.imageFlavor))
                        {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write((BufferedImage) rval, "png", baos);
                            rval = baos.toByteArray();
                        }
                    }
                    
                    return rval;
                }
                
                private boolean isDataMatch(DataFlavor flavor) throws UnsupportedFlavorException, IOException
                {
                    Object cObj = cache.get(flavor);
                    Object obj = getComparableData(flavor);
                    
                    boolean rval = (null == obj && null == cObj);
                    
                    if (!rval && null != obj && null != cObj)
                    {
                        rval = obj.equals(cObj);
                    }
                    
                    return rval;
                }
                
                private void cacheSupportedData() throws UnsupportedFlavorException, IOException
                {
                    HashMap<DataFlavor, Object> cache = new HashMap<DataFlavor, Object>();
                    Iterator<DataFlavor> flavors = ClipboardInterface.getSupportedFlavorsIterator();
                    DataFlavor flavor;
                    
                    while (flavors.hasNext())
                    {
                        flavor = flavors.next();
                        if (clipboard.isDataFlavorAvailable(flavor))
                        {
                            cache.put(flavor, getComparableData(flavor));
                        }
                    }
                    
                    this.cache = cache;
                }
                
                @Override
                public void run()
                {
                    DataFlavor[] prevFlavors = null;
                    DataFlavor[] flavors;
                    DataFlavor flavor;
                    boolean fire;
                    
                    while (!disposed)
                    {
                        flavors = null;
                        fire = Boolean.TRUE;
                        try
                        {
                            cacheSema.acquire();
                        }
                        catch (InterruptedException e)
                        {
                            LLog.e(e);
                        }
                        try
                        {
                            try
                            {
                                flavors = clipboard.getAvailableDataFlavors();
                                
                                something_changed:
                                do
                                {
                                    if (null == prevFlavors && null != flavors)
                                    {
                                        break;
                                    }
                                    else if (null != prevFlavors && null != flavors)
                                    {
                                        if (prevFlavors.length != flavors.length)
                                        {
                                            break;
                                        }
                                        
                                        for (int i=0; i<prevFlavors.length; i++)
                                        {
                                            flavor = flavors[i];
                                            if (!prevFlavors[i].equals(flavor))
                                            {
                                                flavor = null;
                                                break something_changed;
                                            }
                                            if (ClipboardInterface.isFlavorSupported(flavor) && !isDataMatch(flavor))
                                            {
                                                flavor = null;
                                                break something_changed;
                                            }
                                        }
                                    }
                                    
                                    // No Change
                                    fire = Boolean.FALSE;
                                    
                                } while (Boolean.FALSE);
                                
                                if (fire)
                                {
                                    cacheSupportedData();
                                    prevFlavors = flavors;
                                }
                            }
                            catch (Exception e)
                            {
                                LLog.e(e, Boolean.FALSE);
                                fire = Boolean.FALSE;
                            }
                            
                            if (fire)
                            {
                                fireChangeNotification();
                            }
                        }
                        finally {
                            cacheSema.release();
                        }
                        
                        try
                        {
                            Thread.sleep(mac_observer_ms);
                        }
                        catch (InterruptedException e)
                        {
                            LLog.e(e);
                        }
                    }
                }
                
            };
            
            mac_syncObserverCacheCallback = syncObserverCacheCallbackRef[0];
            
            macClipboardChangeObserver.setName("Mac Clipboard Observer");
            macClipboardChangeObserver.setDaemon(Boolean.TRUE);
            macClipboardChangeObserver.setPriority(NORM_PRIORITY);
            macClipboardChangeObserver.start();
        }
        else
        {
            macClipboardChangeObserver = null;
            mac_syncObserverCacheCallback = null;
        }

        // instance config
        setName("Clipboard Monitor");
        setDaemon(Boolean.TRUE);
        setPriority(NORM_PRIORITY);
        start();
    }
    
    public static boolean hasInstance()
    {
        return hasInstance;
    }
    
    public static ClipboardMonitor getInstance()
    {
        ClipboardMonitor rval = selfRef[0];
        
        if (null == rval)
        {
            try
            {
                instanceSema.acquire();
            }
            catch (InterruptedException e)
            {
                LLog.e(e);
            }
            try
            {
                synchronized(selfRef)
                {
                    rval = selfRef[0];
                    if (null == rval)
                    {
                        rval = new ClipboardMonitor();
                        selfRef[0] = rval;
                    }
                }
            }
            finally {
                instanceSema.release();
            }
        }
        
        return rval;
    }
    
    private void fireChangeNotification()
    {
        notificationSema.drainPermits();
        changed = Boolean.TRUE;
        notificationSema.release();
    }
    
    @Override
    public void lostOwnership(Clipboard clipboard, Transferable transferable)
    {
        if (Platform.isMac())
        {
            LLog.w("As of 5/19/2014, MAC does not broadcast clipboard ownership changes, so why is this logged?");
            return;
        }
        fireChangeNotification();
    }
    
    @Override
    public void run()
    {
        try
        {
            idleSema.acquire();
        }
        catch (InterruptedException e)
        {
            LLog.e(e);
        }
        while (!disposed)
        {
            // Gain clipboard ownership so that change notifications can take place again
            // Also secure the contents for internal processing through listeners
            
            do
            {
                try
                {
                    clipboard.setContents(clipboard.getContents(null), this);
                    break;
                }
                catch (Exception e)
                {
                    LLog.e(e, Boolean.FALSE);
                }
                
                try
                {
                    Thread.sleep(delay_ms);
                }
                catch (InterruptedException e)
                {
                    LLog.e(e);
                }
            } while (!disposed);
            
            if (changed)
            {
                changed = Boolean.FALSE;
                
                for(ClipboardListener l : listeners)
                {
                    if (changed)
                    {
                        break;
                    }
                    
                    try
                    {
                        l.onChange(clipboard);
                    }
                    catch (Exception e)
                    {
                        LLog.e(e, Boolean.FALSE);
                    }
                }
            }
            
            try
            {
                idleSema.release();
                notificationSema.acquire();
            }
            catch (InterruptedException e)
            {
                LLog.e(e);
            }
        }
    }
    
    public void dispose()
    {
        disposed = Boolean.TRUE;
    }
    
    public void addListener(ClipboardListener l)
    {
        listeners.add(l);
    }
    
    public boolean removeListener(ClipboardListener l)
    {
        return listeners.remove(l);
    }

    public void setEnabled(boolean enabled)
    {
        if (!enabled)
        {
            try
            {
                idleSema.acquire();
            }
            catch (InterruptedException e)
            {
                LLog.e(e);
            }
        }
        else
        {
            changed = Boolean.FALSE;
            idleSema.release();
        }
    }
    
    /**
     * Useless unless running on the MAC platform which needs a clipboard
     * observer / busy wait thread
     */
    public void syncObserverCache()
    {
        if (!Platform.isMac())
        {
            return;
        }
        mac_syncObserverCacheCallback.run();
    }
}
