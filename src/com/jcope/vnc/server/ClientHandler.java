package com.jcope.vnc.server;

import static com.jcope.vnc.shared.ScreenSelector.getScreenDevicesOrdered;

import java.awt.GraphicsDevice;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.Semaphore;

import javax.swing.SwingUtilities;

import com.jcope.debug.LLog;
import com.jcope.util.TaskDispatcher;
import com.jcope.vnc.server.screen.Manager;
import com.jcope.vnc.server.screen.ScreenListener;
import com.jcope.vnc.shared.AccessModes.ACCESS_MODE;
import com.jcope.vnc.shared.Msg;
import com.jcope.vnc.shared.Msg.CompressedObjectReader;
import com.jcope.vnc.shared.StateMachine.SERVER_EVENT;

public class ClientHandler extends Thread
{
	
	private Socket socket;
	private BufferedInputStream in = null;
	private BufferedOutputStream out = null;
	private ArrayList<Runnable> onDestroyActions = new ArrayList<Runnable>(1);
	private volatile boolean dying = Boolean.FALSE;
	private volatile boolean alive = Boolean.TRUE;
	
	private ClientState clientState = null;
	private DirectRobot dirbot = null;
	
	private TaskDispatcher<Integer> unserializedDispatcher;
    private TaskDispatcher<Integer> serializedDispatcher;
    private boolean isNewFlag = Boolean.TRUE;
    
    private ScreenListener[] screenListenerRef = new ScreenListener[]{null};
    
    private Semaphore sendSema = new Semaphore(1, true);
    private Semaphore serialSema = new Semaphore(1, true);
    volatile int tid = -1;
    
    private Semaphore handleIOSema = new Semaphore(1, true);
    private Semaphore queueSema = new Semaphore(1, true);
    private HashMap<Integer, Boolean> nonSerialEventOutboundQueue = new HashMap<Integer, Boolean>();
    private HashMap<Integer, Object[]> nonSerialEventQueue = new HashMap<Integer, Object[]>();
    private LinkedList<Integer> nonSerialOrderedEventQueue = new LinkedList<Integer>();
	
	public ClientHandler(Socket socket) throws IOException
	{
	    super(toString(socket));
	    this.socket = socket;
		out = new BufferedOutputStream(socket.getOutputStream());
		in = new BufferedInputStream(socket.getInputStream());
		String strID = toString();
		unserializedDispatcher = new TaskDispatcher<Integer>(String.format("Non-serial dispatcher: %s", strID));
        serializedDispatcher = new TaskDispatcher<Integer>(String.format("Serial dispatcher: %s", strID));
	}
	
	public String toString()
	{
	    String rval = toString(socket);
	    
	    return rval;
	}
	
	private static String toString(Socket socket)
	{
	    String rval = String.format("ClientHandler: %s", socketToString(socket));
	    
	    return rval;
	}
	
	private static String socketToString(Socket socket)
	{
		InetAddress addr = socket.getInetAddress();
		return String.format("%s - %s - %d - %d", addr.getHostName(), addr.getHostAddress(), socket.getLocalPort(), socket.getPort());
	}
	
	private static Runnable getUnbindAliasAction(final ClientHandler thiz)
	{
	    Runnable rval = new Runnable()
	    {
	        
	        @Override
	        public void run()
	        {
	            if (AliasRegistry.hasInstance())
	            {
	                AliasRegistry.getInstance().unbind(thiz);
	            }
	        }
	        
	    };
	    
	    return rval;
	}
	
	public boolean getIsNewFlag()
	{
	    return isNewFlag;
	}
	
	public void setIsNewFlag(boolean x)
	{
	    isNewFlag = x;
	}
	
	private Runnable killIOAction = new Runnable()
	{
	    @Override
		public void run()
		{
			try
			{
				in.close();
			}
			catch (IOException e)
			{
				LLog.e(e);
			}
			finally {
				try
				{
					out.close();
				}
				catch (IOException e)
				{
					LLog.e(e);
				}
				finally {
					try
					{
						socket.close();
					}
					catch (IOException e)
					{
						LLog.e(e);
					}
				}
			}
		}
	};
	
	private Runnable releaseIOResources = new Runnable()
	{
        @Override
        public void run()
        {
            JitCompressedEvent jce;
            try
            {
                queueSema.acquire();
            }
            catch (InterruptedException e)
            {
                LLog.e(e);
            }
            try
            {
                synchronized(nonSerialEventQueue) {
                    for (Object[] sargs : nonSerialEventQueue.values())
                    {
                        if (sargs == null)
                        {
                            continue;
                        }
                        jce = (JitCompressedEvent)sargs[0];
                        if (jce != null)
                        {
                            jce.release();
                        }
                    }
                }
            }
            finally {
                queueSema.release();
            }
        }
	};
	
	public void run()
	{
		try
		{
			addOnDestroyAction(killIOAction);
			addOnDestroyAction(releaseIOResources);
			addOnDestroyAction(getUnbindAliasAction(this));
			
			CompressedObjectReader reader = new CompressedObjectReader();
			Object obj = null;
			
			while (!dying)
			{
				try
				{
					obj = reader.readObject(in);
					if (obj == null)
	                {
	                    throw new IOException("Connection reset by peer");
	                }
				}
				catch (IOException e)
				{
					LLog.e(e);
				}
				StateMachine.handleClientInput(this, obj);
				obj = null;
			}
		}
		catch (Exception e)
		{
			LLog.e(e, false);
		}
		finally
		{
			kill();
		}
	}

	public boolean selectGraphicsDevice(int graphicsDeviceID, ACCESS_MODE accessMode, String password)
	{
	    boolean rval;
	    
	    GraphicsDevice[] devices = getScreenDevicesOrdered();
	    GraphicsDevice graphicsDevice = devices[graphicsDeviceID];
        
	    rval = Manager.getInstance().bind(this, graphicsDevice, accessMode, password);
	    
	    return rval;
	}
	
	public void addOnDestroyAction(Runnable r)
	{
		onDestroyActions.add(r);
	}
	
	public void kill()
	{
	    synchronized(this)
	    {
    		if (dying)
    		{
    			return;
    		}
    		dying = true;
	    }
		
	    Exception topE = null;
        
        try
		{
		    Manager.getInstance().unbind(this);
		}
		catch(Exception e)
		{
		    LLog.e(e,false);
		}
		
		try
		{
			for (Runnable r : onDestroyActions)
			{
				try
				{
				    r.run();
				}
				catch(Exception e)
				{
				    if (topE == null)
				    {
				        topE = e;
				    }
				    else
				    {
				        System.err.println(e.getMessage());
				        e.printStackTrace(System.err);
				    }
				}
			}
			if (topE != null)
            {
                throw new RuntimeException(topE);
            }
		}
		finally {
			onDestroyActions.clear();
			onDestroyActions = null;
			try
			{
			    try
			    {
			        serializedDispatcher.dispose();
			    }
			    finally {
			        unserializedDispatcher.dispose();
			    }
			}
			finally {
    			SwingUtilities.invokeLater(new Runnable() {
    				
    				@Override
    				public void run()
    				{
    					try
    					{
    						try
    						{
    							serializedDispatcher.join();
    						}
    						finally {
    							unserializedDispatcher.join();
    						}
    					}
    					catch (InterruptedException e)
    					{
    						LLog.e(e, Boolean.FALSE);
    					}
    				}
    				
    			});
			}
		}
		
		alive = false;
	}
	
	public boolean isRunning()
	{
		return !dying;
	}
	
	public boolean isDead()
	{
		return !alive;
	}
	
	public ClientState getClientState()
	{
		return clientState;
	}
	
	public void initClientState()
	{
		clientState = new ClientState();
	}

	public ScreenListener getScreenListener(final DirectRobot dirbot)
	{
		ScreenListener l = screenListenerRef[0];
		if (l == null || dirbot != this.dirbot)
		{
			this.dirbot = dirbot;
			l = new ScreenListener() {
			    
				@Override
				public void onScreenChange(int segmentID)
				{
					sendEvent(SERVER_EVENT.SCREEN_SEGMENT_CHANGED, Integer.valueOf(segmentID));
				}
			};
			screenListenerRef[0] = l;
		}
		return l;
		
	}
	
	public void sendEvent(JitCompressedEvent jce)
    {
        _sendEvent(jce.getEvent(), jce, (Object[]) null);
    }
	
	public void sendEvent(SERVER_EVENT event)
    {
        sendEvent(event, (Object[]) null);
    }
	
	public void sendEvent(final SERVER_EVENT event, final Object... args)
	{
	    _sendEvent(event, null, args);
	}

	public void _sendEvent(final SERVER_EVENT event, final JitCompressedEvent jce, final Object... args)
	{
	    try
        {
            handleIOSema.acquire();
        }
        catch (InterruptedException e)
        {
            LLog.e(e);
        }
	    try
        {
	        nts_sendEvent(event, jce, args);
        }
        finally {
            handleIOSema.release();
        }
	}
	
	private void nts_sendEvent(final SERVER_EVENT event, final JitCompressedEvent jce, final Object... args)
	{
	    int tidTmp;
	    TaskDispatcher<Integer> dispatcher;
	    boolean dispatch;
	    boolean isMutable;
	    
		if (event.isSerial())
        {
		    try
		    {
		        serialSema.acquire();
		    }
            catch (InterruptedException e)
            {
                LLog.e(e);
            }
		    try
		    {
		        tidTmp = tid;
		        tidTmp++;
                if (tidTmp < 0)
                {
                    tidTmp = 0;
                }
                tid = tidTmp;
            }
    		finally {
    		    serialSema.release();
    		}
            dispatcher = serializedDispatcher;
            dispatch = Boolean.TRUE;
        }
		else
		{
		    tidTmp = getNonSerialTID(event, args, 0);
		    dispatcher = unserializedDispatcher;
		    // TODO: only dispatch if we know for sure that the arguments have changed
		    if ((isMutable = event.hasMutableArgs()) || !unserializedDispatcher.queueContains(tidTmp))
		    {
		        if (event == SERVER_EVENT.SCREEN_SEGMENT_UPDATE)
                {
                    dispatch = Boolean.TRUE;
                }
		        else
		        {
    		        try
    	            {
    	                queueSema.acquire();
    	            }
    	            catch (InterruptedException e)
    	            {
    	                LLog.e(e);
    	            }
    	            try
    	            {
    	                synchronized(nonSerialEventQueue) {synchronized(nonSerialEventOutboundQueue) {synchronized(nonSerialOrderedEventQueue) {
    	                    if (nonSerialEventOutboundQueue.get(tidTmp) == null)
                            {
                                dispatch = Boolean.TRUE;
                                nonSerialEventOutboundQueue.put(tidTmp, Boolean.TRUE);
                                nonSerialOrderedEventQueue.addLast(tidTmp);
                            }
                            else
                            {
                                Object[] sargs = null;
                                dispatch = Boolean.FALSE;
                                if (isMutable || (sargs = nonSerialEventQueue.get(tidTmp)) == null)
                                {
                                    JitCompressedEvent jce2;
                                    if (isMutable)
                                    {
                                        sargs = nonSerialEventQueue.get(tidTmp);
                                    }
                                    if (sargs == null)
                                    {
                                        sargs = new Object[]{ jce, args };
                                    }
                                    else
                                    {
                                        jce2 = (JitCompressedEvent) sargs[0];
                                        if (jce2 != null)
                                        {
                                            jce2.release();
                                        }
                                        sargs[0] = jce;
                                        sargs[1] = args;
                                    }
                                    if (jce != null)
                                    {
                                        jce.acquire();
                                    }
                                }
                            }
                        }}}
    	            }
    	            finally {
    	                queueSema.release();
    	            }
		        }
		    }
		    else
		    {
		        dispatch = Boolean.FALSE;
		    }
		        
		}
		if (dispatch)
		{
		    Runnable r = new Runnable() {
	            
	            @Override
	            public void run()
	            {
	                boolean killSelf = true;
	                boolean flushed = false;
	                try
	                {
	                    try
	                    {
	                        sendSema.acquire();
	                    }
	                    catch (InterruptedException e)
	                    {
	                        LLog.e(e);
	                    }
	                    try
	                    {
	                        Msg.send(out, jce, event, args);
	                        if (serializedDispatcher.isEmpty() && unserializedDispatcher.isEmpty())
	                        {
	                            flushed = true;
	                            out.flush();
	                        }
	                        switch(event)
	                        {
                                case AUTHORIZATION_UPDATE:
                                    if (!flushed)
                                    {
                                        flushed = true;
                                        out.flush();
                                    }
                                    if (!((Boolean) args[0]))
                                    {
                                        SwingUtilities.invokeLater(new Runnable() {

                                            @Override
                                            public void run()
                                            {
                                                kill();
                                            }
                                            
                                        });
                                    }
                                    break;
                                case ALIAS_CHANGED:
                                case ALIAS_DISCONNECTED:
                                case ALIAS_REGISTERED:
                                case ALIAS_UNREGISTERED:
                                case CHAT_MSG_TO_ALL:
                                case CHAT_MSG_TO_USER:
                                case CLIENT_ALIAS_UPDATE:
                                case CONNECTION_CLOSED:
                                case CONNECTION_ESTABLISHED:
                                case CURSOR_GONE:
                                case CURSOR_MOVE:
                                case FAILED_AUTHORIZATION:
                                case NUM_SCREENS_CHANGED:
                                case SCREEN_GONE:
                                case SCREEN_RESIZED:
                                case SCREEN_SEGMENT_CHANGED:
                                case SCREEN_SEGMENT_SIZE_UPDATE:
                                case SCREEN_SEGMENT_UPDATE:
                                case READ_INPUT_EVENTS:
                                    break;
	                        }
	                    }
	                    catch (IOException e)
	                    {
	                        LLog.e(e);
	                    }
	                    finally {
	                        sendSema.release();
	                    }
	                    killSelf = false;
	                }
	                finally {
	                    if (killSelf)
	                    {
	                        kill();
	                    }
	                }
	            }
	        };
	        
	        Runnable rOnDestroy;
	        
	        if (jce == null)
	        {
	            rOnDestroy = null;
	        }
	        else
	        {
	            jce.acquire();
	            rOnDestroy = jce.getOnDestroy();
	        } 
	        
	        dispatcher.dispatch(tidTmp, r, rOnDestroy);
		}
	}
	
	private int getNonSerialTID(SERVER_EVENT event, Object[] refStack, int idxSegmentID)
    {
	    int rval;
	    if (event == SERVER_EVENT.SCREEN_SEGMENT_CHANGED || event == SERVER_EVENT.SCREEN_SEGMENT_UPDATE)
        {
	        rval = ((Integer)refStack[idxSegmentID]) + 2;
            if (event == SERVER_EVENT.SCREEN_SEGMENT_UPDATE)
            {
                rval += SERVER_EVENT.getMaxOrdinal();
            }
            else
            {
                rval = -rval;
            }
        }
        else
        {
            rval = event.ordinal();
        }
	    
	    return rval;
    }

    public Object getSegmentOptimized(int segmentID)
	{
	    Object rval = Manager.getInstance().getSegmentOptimized(dirbot, segmentID);
	    
	    return rval;
	}
	
	public DirectRobot getDirbot()
	{
	    return dirbot;
	}
	
	public void handleEventAck(SERVER_EVENT event, Object[] refStack, int idxSegmentID)
	{
	    ArrayList<Object[]> plist = new ArrayList<Object[]>();
	    int tTid = getNonSerialTID(event, refStack, idxSegmentID);
	    int tid;
	    Object[] sargs;
	    JitCompressedEvent jce;
	    
	    try
        {
            handleIOSema.acquire();
        }
        catch (InterruptedException e)
        {
            LLog.e(e);
        }
	    try
	    {
    	    try
            {
                queueSema.acquire();
            }
            catch (InterruptedException e)
            {
                LLog.e(e);
            }
            try
            {
                synchronized(nonSerialEventQueue) {synchronized(nonSerialEventOutboundQueue) {synchronized(nonSerialOrderedEventQueue) {
                    do
                    {
                        tid = nonSerialOrderedEventQueue.removeFirst();
                        nonSerialEventOutboundQueue.remove(tid);
                        sargs = nonSerialEventQueue.remove(tid);
                        if (sargs != null)
                        {
                            plist.add(sargs);
                        }
                    } while (tid != tTid);
                }}}
            }
            finally {
                queueSema.release();
            }
            
            Exception firstE = null;
            for (Object[] targs : plist)
            {
                jce = (JitCompressedEvent) targs[0];
                try
                {
                    if (firstE == null)
                    {
                        nts_sendEvent(event, jce, (Object[]) targs[1]);
                    }
                }
                catch (Exception e)
                {
                    if (firstE == null)
                    {
                        firstE = e;
                    }
                    else
                    {
                        throw new RuntimeException(e);
                    }
                }
                finally {
                    if (jce != null)
                    {
                        jce.release();
                    }
                }
            }
            if (firstE != null)
            {
                throw new RuntimeException(firstE);
            }
	    }
	    finally {
	        handleIOSema.release();
	    }
	}
}
