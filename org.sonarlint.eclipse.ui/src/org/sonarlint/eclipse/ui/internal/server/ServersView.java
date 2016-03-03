package org.sonarlint.eclipse.ui.internal.server;

import java.util.HashSet;
import java.util.Set;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.IDecoratorManager;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionContext;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.forms.widgets.Form;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;
import org.eclipse.ui.navigator.CommonViewerSiteFactory;
import org.eclipse.ui.navigator.ICommonViewerSite;
import org.eclipse.ui.navigator.NavigatorActionService;
import org.eclipse.ui.part.PageBook;
import org.sonarlint.eclipse.core.internal.SonarLintCorePlugin;
import org.sonarlint.eclipse.core.internal.server.IServer;
import org.sonarlint.eclipse.core.internal.server.IServerLifecycleListener;
import org.sonarlint.eclipse.core.internal.server.IServerListener;
import org.sonarlint.eclipse.ui.internal.Messages;
import org.sonarlint.eclipse.ui.internal.server.wizard.NewServerLocationWizard;

/**
 * A view of servers, their modules, and status.
 */
public class ServersView extends CommonNavigator {
  private static final String SERVERS_VIEW_CONTEXT = "org.eclipse.ui.sonarlintServerViewScope";

  protected CommonViewer tableViewer;
  private Control mainPage;
  private Control noServersPage;
  private PageBook book;

  private IServerLifecycleListener serverResourceListener;
  private IServerListener serverListener;

  // servers that are currently publishing and starting
  protected static Set<String> starting = new HashSet<>(4);
  protected boolean animationActive = false;
  protected boolean stopAnimation = false;

  /**
   * ServersView constructor comment.
   */
  public ServersView() {
    super();
  }

  @Override
  public void createPartControl(Composite parent) {
    // Add PageBook as parent composite
    FormToolkit toolkit = new FormToolkit(parent.getDisplay());
    book = new PageBook(parent, SWT.NONE);
    super.createPartControl(book);
    // Main page for the Servers tableViewer
    mainPage = getCommonViewer().getControl();
    // Page prompting to define a new server
    noServersPage = createDefaultPage(toolkit);
    book.showPage(mainPage);

    IContextService contextSupport = (IContextService) getSite().getService(IContextService.class);
    contextSupport.activateContext(SERVERS_VIEW_CONTEXT);
    deferInitialization();
  }

  /**
   * Creates a page displayed when there are no servers defined.
   * 
   * @param kit
   * @return Control
   */
  private Control createDefaultPage(FormToolkit kit) {
    Form form = kit.createForm(book);
    Composite body = form.getBody();
    GridLayout layout = new GridLayout(2, false);
    body.setLayout(layout);

    Link hlink = new Link(body, SWT.NONE);
    hlink.setText(Messages.ServersView_noServers);
    hlink.setBackground(book.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
    GridData gd = new GridData(SWT.LEFT, SWT.FILL, true, false);
    hlink.setLayoutData(gd);
    hlink.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        NewServerLocationWizard wizard = new NewServerLocationWizard();
        WizardDialog wd = new WizardDialog(book.getShell(), wizard);
        if (wd.open() == Window.OK) {
          toggleDefaultPage();
        }
      }
    });

    // Create the context menu for the default page
    final CommonViewer commonViewer = this.getCommonViewer();
    if (commonViewer != null) {
      ICommonViewerSite commonViewerSite = CommonViewerSiteFactory
        .createCommonViewerSite(this.getViewSite());

      if (commonViewerSite != null) {
        // Note: actionService cannot be null
        final NavigatorActionService actionService = new NavigatorActionService(commonViewerSite,
          commonViewer, commonViewer.getNavigatorContentService());

        MenuManager menuManager = new MenuManager("#PopupMenu");
        menuManager.addMenuListener(new IMenuListener() {
          @Override
          public void menuAboutToShow(IMenuManager mgr) {
            ISelection selection = commonViewer.getSelection();
            actionService.setContext(new ActionContext(selection));
            actionService.fillContextMenu(mgr);
          }
        });
        Menu menu = menuManager.createContextMenu(body);

        // It is necessary to set the menu in two places:
        // 1. The white space in the server view
        // 2. The text and link in the server view. If this menu is not set, if the
        // user right clicks on the text or uses shortcut keys to open the context menu,
        // the context menu will not come up
        body.setMenu(menu);
        hlink.setMenu(menu);
      }
    }

    return form;
  }

  /**
   * Switch between the servers and default/empty page. 
   * 
   */
  void toggleDefaultPage() {
    if (tableViewer.getTree().getItemCount() < 1) {
      book.showPage(noServersPage);
    } else {
      book.showPage(mainPage);
    }
  }

  private void deferInitialization() {
    Job job = new Job(Messages.jobInitializingServersView) {
      @Override
      public IStatus run(IProgressMonitor monitor) {
        IServer[] servers = SonarLintCorePlugin.getDefault().getServers();
        int size = servers.length;
        for (int i = 0; i < size; i++) {
          // TODO Load associated projects? ((Server) servers[i]).getAllModules().iterator();
        }
        deferredInitialize();
        return Status.OK_STATUS;
      }
    };

    job.setSystem(true);
    job.setPriority(Job.SHORT);
    job.schedule();
  }

  protected void deferredInitialize() {
    addListener();

    Display.getDefault().asyncExec(new Runnable() {
      @Override
      public void run() {
        try {
          tableViewer = getCommonViewer();
          getSite().setSelectionProvider(tableViewer);

          // init the tooltip
          ServerToolTip toolTip = new ServerToolTip(tableViewer.getTree());
          toolTip.setShift(new Point(10, 3));
          toolTip.setPopupDelay(400); // in ms
          toolTip.setHideOnMouseDown(true);
          toolTip.activate();

          try {
            if (tableViewer.getTree().getItemCount() > 0) {
              Object obj = tableViewer.getTree().getItem(0).getData();
              tableViewer.setSelection(new StructuredSelection(obj));
            } else {
              toggleDefaultPage();
            }
          } catch (Exception e) {
            throw new IllegalStateException("Unable to update servers", e);
          }
        } catch (Exception e) {
          // ignore - view has already been closed
        }
      }
    });

  }

  protected void refreshServerContent(final IServer server) {
    Display.getDefault().asyncExec(new Runnable() {
      @Override
      public void run() {
        if (!tableViewer.getTree().isDisposed())
          tableViewer.refresh(server, true);
      }
    });
  }

  protected void refreshServerState(final IServer server) {
    Display.getDefault().asyncExec(new Runnable() {
      @Override
      public void run() {
        IDecoratorManager dm = PlatformUI.getWorkbench().getDecoratorManager();
        dm.update(ServerDecorator.ID);
        if (tableViewer != null) {
          tableViewer.setSelection(tableViewer.getSelection());
        }
      }
    });
  }

  protected void addListener() {
    // To enable the UI updating of servers and its childrens
    serverResourceListener = new IServerLifecycleListener() {
      @Override
      public void serverAdded(IServer server) {
        addServer(server);
        server.addServerListener(serverListener);
      }

      @Override
      public void serverChanged(IServer server) {
        refreshServerContent(server);
      }

      @Override
      public void serverRemoved(IServer server) {
        removeServer(server);
        server.removeServerListener(serverListener);
      }
    };
    SonarLintCorePlugin.getDefault().addServerLifecycleListener(serverResourceListener);

    serverListener = new IServerListener() {

      @Override
      public void serverChanged(IServer server) {
        refreshServerState(server);
      }
    };

    // add listeners to servers
    for (IServer server : SonarLintCorePlugin.getDefault().getServers()) {
      server.addServerListener(serverListener);
    }

  }

  protected void addServer(final IServer server) {
    Display.getDefault().asyncExec(new Runnable() {
      @Override
      public void run() {
        tableViewer.add(tableViewer.getInput(), server);
        toggleDefaultPage();
      }
    });
  }

  protected void removeServer(final IServer server) {
    Display.getDefault().asyncExec(new Runnable() {
      @Override
      public void run() {
        tableViewer.remove(server);
        toggleDefaultPage();
      }
    });
  }

  @Override
  public void dispose() {
    SonarLintCorePlugin.getDefault().removeServerLifecycleListener(serverResourceListener);
    super.dispose();
  }

  protected void stopThread() {
    stopAnimation = true;
  }
}