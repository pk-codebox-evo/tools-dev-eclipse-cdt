/*
 * Created on Apr 14, 2004
 *
 * To change the template for this generated file go to
 * Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */
package org.eclipse.cdt.debug.internal.ui;

import java.util.Map;

import org.eclipse.cdt.debug.core.ICBreakpointListener;
import org.eclipse.cdt.debug.core.model.ICBreakpoint;
import org.eclipse.cdt.debug.ui.CDebugUIPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.swt.widgets.Display;

/**
 * Provides UI-related handles for the breakpoint events. 
 */
public class CBreakpointUpdater implements ICBreakpointListener
{
	private static CBreakpointUpdater fInstance;
	
	public static CBreakpointUpdater getInstance()
	{
		if ( fInstance == null )
		{
			fInstance = new CBreakpointUpdater(); 
		}
		return fInstance;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.cdt.debug.core.ICBreakpointListener#installingBreakpoint(org.eclipse.debug.core.model.IDebugTarget, org.eclipse.debug.core.model.IBreakpoint)
	 */
	public boolean installingBreakpoint( IDebugTarget target, IBreakpoint breakpoint )
	{
		return true;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.cdt.debug.core.ICBreakpointListener#breakpointInstalled(org.eclipse.debug.core.model.IDebugTarget, org.eclipse.debug.core.model.IBreakpoint)
	 */
	public void breakpointInstalled( IDebugTarget target, final IBreakpoint breakpoint )
	{
		asyncExec( new Runnable()
						{
							public void run()
							{
								try
								{
									((ICBreakpoint)breakpoint).incrementInstallCount();
									DebugPlugin.getDefault().getBreakpointManager().fireBreakpointChanged( breakpoint );
								}
								catch( CoreException e )
								{
									CDebugUIPlugin.log( e.getStatus() );
								}
							}
						} );
	}

	/* (non-Javadoc)
	 * @see org.eclipse.cdt.debug.core.ICBreakpointListener#breakpointChanged(org.eclipse.debug.core.model.IDebugTarget, org.eclipse.debug.core.model.IBreakpoint, java.util.Map)
	 */
	public void breakpointChanged( IDebugTarget target, final IBreakpoint breakpoint, final Map attributes )
	{
		asyncExec( new Runnable()
						{
							public void run()
							{
								try
								{
									Boolean enabled = (Boolean)attributes.get( IBreakpoint.ENABLED );
									breakpoint.setEnabled( ( enabled != null ) ? enabled.booleanValue() : false );
									Integer ignoreCount = (Integer)attributes.get( ICBreakpoint.IGNORE_COUNT );
									((ICBreakpoint)breakpoint).setIgnoreCount( ( ignoreCount != null ) ? ignoreCount.intValue() : 0 );
									String condition = (String)attributes.get( ICBreakpoint.CONDITION );
									((ICBreakpoint)breakpoint).setCondition( ( condition != null ) ? condition : "" );
								}
								catch( CoreException e )
								{
									CDebugUIPlugin.log( e.getStatus() );
								}
							}
						} );
	}

	/* (non-Javadoc)
	 * @see org.eclipse.cdt.debug.core.ICBreakpointListener#breakpointRemoved(org.eclipse.debug.core.model.IDebugTarget, org.eclipse.debug.core.model.IBreakpoint)
	 */
	public void breakpointRemoved( IDebugTarget target, final IBreakpoint breakpoint )
	{
		asyncExec( new Runnable()
						{
							public void run()
							{
								try
								{
									int installCount = ((ICBreakpoint)breakpoint).decrementInstallCount();
									if ( installCount == 0 )
										breakpoint.delete();
								}
								catch( CoreException e )
								{
									CDebugUIPlugin.log( e.getStatus() );
								}
							}
						} );
	}

	public void dispose()
	{
	}

	private void asyncExec( Runnable r )
	{
		Display display = DebugUIPlugin.getStandardDisplay();
		if ( display != null )
			display.asyncExec( r );
	}
}
