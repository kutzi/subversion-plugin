/*
 * The MIT License
 *
 * Copyright (c) 2004-2010, Sun Microsystems, Inc., Kohsuke Kawaguchi, Fulvio Cavarretta,
 * Jean-Baptiste Quenot, Luca Domenico Milanesio, Renaud Bruyeron, Stephen Connolly,
 * Tom Huybrechts, Yahoo! Inc., Manufacture Francaise des Pneumatiques Michelin,
 * Romain Seguy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.scm.subversion;

import hudson.Extension;
import hudson.Util;
import hudson.scm.SubversionSCM.External;
import hudson.util.IOException2;

import org.apache.commons.lang.time.FastDateFormat;
import org.kohsuke.stapler.DataBoundConstructor;
import org.tmatesoft.svn.core.SVNCancelException;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNUpdateClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link WorkspaceUpdater} that does a fresh check out.
 *
 * @author Kohsuke Kawaguchi
 */
public class CheckoutUpdater extends WorkspaceUpdater {
    private static final long serialVersionUID = -3502075714024708011L;

    private static final FastDateFormat fmt = FastDateFormat.getInstance("''yyyy-MM-dd'T'HH:mm:ss.SSS Z''");
    
    @DataBoundConstructor
    public CheckoutUpdater() {}

    @Override
    public UpdateTask createTask() {
        return new UpdateTask() {
            private static final long serialVersionUID = 8349986526712487762L;

            @Override
            public List<External> perform() throws IOException, InterruptedException {
                final SVNUpdateClient svnuc = clientManager.getUpdateClient();
                final List<External> externals = new ArrayList<External>(); // store discovered externals to here

                listener.getLogger().println("Cleaning local Directory " + location.getLocalDir());
                Util.deleteContentsRecursive(new File(ws, location.getLocalDir()));

                // buffer the output by a separate thread so that the update operation
                // won't be blocked by the remoting of the data
                PipedOutputStream pos = new PipedOutputStream();
                
                StreamCopyThread sct = new StreamCopyThread("svn log copier", new PipedInputStream(pos), listener.getLogger());
                sct.start();

                try {
                	
                	SVNRevision r = getRevision(location);

                    String revisionName = r.getDate() != null ?
                    		fmt.format(r.getDate()) : r.toString();
                	
                    listener.getLogger().println("Checking out " + location.remote + " at revision " + revisionName);

                    File local = new File(ws, location.getLocalDir());
                    SubversionUpdateEventHandler eventHandler = new SubversionUpdateEventHandler(new PrintStream(pos), externals, local, location.getLocalDir());
                    svnuc.setEventHandler(eventHandler);
                    svnuc.setExternalsHandler(eventHandler);
                    svnuc.setIgnoreExternals(location.isIgnoreExternalsOption());
                    
                    SVNDepth svnDepth = getSvnDepth(location.getDepthOption());
                    svnuc.doCheckout(location.getSVNURL(), local.getCanonicalFile(), SVNRevision.HEAD, r, svnDepth, true);
                } catch (SVNCancelException e) {
                    if (isAuthenticationFailedError(e)) {
                        e.printStackTrace(listener.error("Failed to check out " + location.remote));
                        return null;
                    } else {
                        listener.error("Subversion checkout has been canceled");
                        throw (InterruptedException)new InterruptedException().initCause(e);
                    }
                } catch (SVNException e) {
                    e.printStackTrace(listener.error("Failed to check out " + location.remote));
                    throw new IOException("Failed to check out " + location.remote, e) ;
                } finally {
                    try {
                        pos.close();
                    } finally {
                        try {
                            sct.join(); // wait for all data to be piped.
                        } catch (InterruptedException e) {
                            throw new IOException2("interrupted", e);
                        }
                    }
                }

                return externals;
            }
        };
    }
    
    /**
     * {@link Thread} that copies an {@link InputStream} line wise to a {@link PrintStream}.
     *
     * @author Kohsuke Kawaguchi
     * @author Christoph Kutzinski
     */
    private static class StreamCopyThread extends Thread {
        private final BufferedReader in;
        private final PrintStream out;

        public StreamCopyThread(String threadName, InputStream in, PrintStream out) {
            super(threadName);
			this.in = new BufferedReader(new InputStreamReader(in));
            if (out == null) {
                throw new NullPointerException("out is null");
            }
            this.out = out;
        }

        @Override
        public void run() {
            try {
                try {
                    String line;
                    while ((line = in.readLine()) != null)
                        out.println(line);
                } finally {
                    // it doesn't make sense not to close InputStream that's already EOF-ed,
                    // so there's no 'closeIn' flag.
                    in.close();
                }
            } catch (IOException e) {
            	e.printStackTrace(out);
            }
        }
    }

    @Extension
    public static class DescriptorImpl extends WorkspaceUpdaterDescriptor {
        @Override
        public String getDisplayName() {
            return Messages.CheckoutUpdater_DisplayName();
        }
    }
}
