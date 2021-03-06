/*
 * Copyright (c) 2009. The LoPSideD implementation of the Linked Process
 * protocol is an open-source project founded at the Center for Nonlinear Studies
 * at the Los Alamos National Laboratory in Los Alamos, New Mexico. Please visit
 * http://linkedprocess.org and LICENSE.txt for more information.
 */

package org.linkedprocess.villein.patterns;

import org.linkedprocess.LopError;
import org.linkedprocess.LinkedProcess;
import org.linkedprocess.farm.os.VmBindings;
import org.linkedprocess.villein.Handler;
import org.linkedprocess.villein.proxies.FarmProxy;
import org.linkedprocess.villein.proxies.JobProxy;
import org.linkedprocess.villein.proxies.ResultHolder;
import org.linkedprocess.villein.proxies.VmProxy;

import java.util.Set;
import java.util.logging.Logger;

/**
 * SynchronousPattern provides methods that wait for commands to complete before continuing.
 * This pattern runs against the design philosophy of XMPP in which communication should be asynchronous.
 * However, practically speaking, there are many situations where it is easier to wait for the command to complete then to deal with handlers.
 * All of the methods provided by this pattern allow for a timeout value to be provided.
 * If the command takes longer than this timeout value, then a TimeoutException is thrown.
 *
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 * @version LoPSideD 0.1
 */
public class SynchronousPattern {
    private static final Logger LOGGER = LinkedProcess.getLogger(SynchronousPattern.class);

    /**
     * Puts a monitor on wait for a certain number of milliseconds.
     *
     * @param monitor the monitor object to wait
     * @param timeout the number of milliseconds to wait (use -1 to wait indefinately)
     */
    private static void monitorSleep(final Object monitor, final long timeout) {
        try {
            synchronized (monitor) {
                if (timeout > 0)
                    monitor.wait(timeout);
                else
                    monitor.wait();
            }
        } catch (InterruptedException e) {
            LOGGER.warning(e.getMessage());
        }
    }

    /**
     * Spawn a virtual machine and wait for the command to complete.
     *
     * @param farmProxy the farm on which to spawn the virtual machine
     * @param vmSpecies the species of the virtual machine to spawn
     * @param timeout   the number of milliseconds to spend on this command before a TimeoutException is thrown (use -1 to wait indefinately)
     * @return the result of the command
     * @throws TimeoutException is thrown when the command takes longer than the provided timeout in milliseconds
     */
    public static ResultHolder<VmProxy> spawnVm(final FarmProxy farmProxy, final String vmSpecies, final long timeout) throws TimeoutException {
        final Object monitor = new Object();
        final ResultHolder<VmProxy> resultHolder = new ResultHolder<VmProxy>();

        Handler<VmProxy> resultHandler = new Handler<VmProxy>() {
            public void handle(VmProxy vmProxy) {
                resultHolder.setSuccess(vmProxy);
                farmProxy.addVmProxy(vmProxy);
                synchronized (monitor) {
                    monitor.notify();
                }
            }
        };
        Handler<LopError> errorHandler = new Handler<LopError>() {
            public void handle(LopError lopError) {
                resultHolder.setLopError(lopError);
                synchronized (monitor) {
                    monitor.notify();
                }
            }
        };

        farmProxy.spawnVm(vmSpecies, resultHandler, errorHandler);
        SynchronousPattern.monitorSleep(monitor, timeout);
        if (resultHolder.isEmpty())
            throw new TimeoutException("spawn_vm timedout after " + timeout + "ms.");

        return resultHolder;
    }

    /**
     * Submit a job to a virtual machine for evaluation/execution.
     *
     * @param vmProxy  the virtual machine on which to execute the command
     * @param jobProxy the job to be excecuted
     * @param timeout  the number of milliseconds to spend on this command before a TimeoutException is thrown (use -1 to wait indefinately)
     * @return the result of the command
     * @throws TimeoutException is thrown when the command takes longer than the provided timeout in milliseconds
     */
    public static ResultHolder<JobProxy> submitJob(final VmProxy vmProxy, final JobProxy jobProxy, final long timeout) throws TimeoutException {
        final Object monitor = new Object();
        final ResultHolder<JobProxy> resultHolder = new ResultHolder<JobProxy>();

        Handler<JobProxy> submitJobHandler = new Handler<JobProxy>() {
            public void handle(JobProxy jobStruct) {
                resultHolder.setSuccess(jobStruct);
                synchronized (monitor) {
                    monitor.notify();
                }
            }
        };

        vmProxy.submitJob(jobProxy, submitJobHandler, submitJobHandler);
        SynchronousPattern.monitorSleep(monitor, timeout);
        if (resultHolder.isEmpty())
            throw new TimeoutException("submit_job timedout after " + timeout + "ms.");

        return resultHolder;
    }

    /**
     * @param vmProxy  the virtual machine on which to execute the command
     * @param jobProxy the job to ping on the virtual machine
     * @param timeout  the number of milliseconds to spend on this command before a TimeoutException is thrown (use -1 to wait indefinately)
     * @return the result of the command
     * @throws TimeoutException is thrown when the command takes longer than the provided timeout in milliseconds
     */
    public static ResultHolder<LinkedProcess.JobStatus> pingJob(final VmProxy vmProxy, final JobProxy jobProxy, final long timeout) throws TimeoutException {
        final Object monitor = new Object();
        final ResultHolder<LinkedProcess.JobStatus> resultHolder = new ResultHolder<LinkedProcess.JobStatus>();

        Handler<LinkedProcess.JobStatus> resultHandler = new Handler<LinkedProcess.JobStatus>() {
            public void handle(LinkedProcess.JobStatus jobStatus) {
                resultHolder.setSuccess(jobStatus);
                synchronized (monitor) {
                    monitor.notify();
                }
            }
        };
        Handler<LopError> errorHandler = new Handler<LopError>() {
            public void handle(LopError lopError) {
                resultHolder.setLopError(lopError);
                synchronized (monitor) {
                    monitor.notify();
                }
            }
        };

        vmProxy.pingJob(jobProxy, resultHandler, errorHandler);
        SynchronousPattern.monitorSleep(monitor, timeout);
        if (resultHolder.isEmpty())
            throw new TimeoutException("ping_job timedout after " + timeout + "ms.");

        return resultHolder;
    }

    /**
     * @param vmProxy  the virtual machine on which to execute the command
     * @param jobProxy the job to abort on the virtual machine
     * @param timeout  the number of milliseconds to spend on this command before a TimeoutException is thrown (use -1 to wait indefinately)
     * @return the result of the command
     * @throws TimeoutException is thrown when the command takes longer than the provided timeout in milliseconds
     */
    public static ResultHolder<String> abortJob(final VmProxy vmProxy, final JobProxy jobProxy, final long timeout) throws TimeoutException {
        final Object monitor = new Object();
        final ResultHolder<String> resultHolder = new ResultHolder<String>();

        Handler<String> resultHandler = new Handler<String>() {
            public void handle(String jobId) {
                resultHolder.setSuccess(jobId);
                synchronized (monitor) {
                    monitor.notify();
                }
            }
        };
        Handler<LopError> errorHandler = new Handler<LopError>() {
            public void handle(LopError lopError) {
                resultHolder.setLopError(lopError);
                synchronized (monitor) {
                    monitor.notify();
                }
            }
        };

        vmProxy.abortJob(jobProxy, resultHandler, errorHandler);

        SynchronousPattern.monitorSleep(monitor, timeout);
        if (resultHolder.isEmpty())
            throw new TimeoutException("abort_job timedout after " + timeout + "ms.");

        return resultHolder;
    }

    /**
     * @param vmProxy    the virtual machine on which to execute the command
     * @param vmBindings the bindings to set on the virtual machine
     * @param timeout    the number of milliseconds to spend on this command before a TimeoutException is thrown (use -1 to wait indefinately)
     * @return the result of the command
     * @throws TimeoutException is thrown when the command takes longer than the provided timeout in milliseconds
     */
    public static ResultHolder<VmBindings> setBindings(final VmProxy vmProxy, VmBindings vmBindings, final long timeout) throws TimeoutException {
        final Object monitor = new Object();
        final ResultHolder<VmBindings> resultHolder = new ResultHolder<VmBindings>();

        Handler<VmBindings> resultHandler = new Handler<VmBindings>() {
            public void handle(VmBindings vmBindings) {
                resultHolder.setSuccess(vmBindings);
                synchronized (monitor) {
                    monitor.notify();
                }
            }
        };
        Handler<LopError> errorHandler = new Handler<LopError>() {
            public void handle(LopError lopError) {
                resultHolder.setLopError(lopError);
                synchronized (monitor) {
                    monitor.notify();
                }
            }
        };
        vmProxy.setBindings(vmBindings, resultHandler, errorHandler);

        SynchronousPattern.monitorSleep(monitor, timeout);
        if (resultHolder.isEmpty())
            throw new TimeoutException("set manage_bindings timedout after " + timeout + "ms.");

        return resultHolder;

    }

    /**
     * @param vmProxy      the virtual machine on which to execute the command
     * @param bindingNames the name of the bindings to retrieve from the virtual machine
     * @param timeout      the number of milliseconds to spend on this command before a TimeoutException is thrown (use -1 to wait indefinately)
     * @return the result of the command
     * @throws TimeoutException is thrown when the command takes longer than the provided timeout in milliseconds
     */
    public static ResultHolder<VmBindings> getBindings(final VmProxy vmProxy, Set<String> bindingNames, final long timeout) throws TimeoutException {
        final Object monitor = new Object();
        final ResultHolder<VmBindings> resultHolder = new ResultHolder<VmBindings>();

        Handler<VmBindings> resultHandler = new Handler<VmBindings>() {
            public void handle(VmBindings vmBindings) {
                resultHolder.setSuccess(vmBindings);
                synchronized (monitor) {
                    monitor.notify();
                }
            }
        };
        Handler<LopError> errorHandler = new Handler<LopError>() {
            public void handle(LopError lopError) {
                resultHolder.setLopError(lopError);
                synchronized (monitor) {
                    monitor.notify();
                }
            }
        };
        vmProxy.getBindings(bindingNames, resultHandler, errorHandler);

        SynchronousPattern.monitorSleep(monitor, timeout);
        if (resultHolder.isEmpty())
            throw new TimeoutException("get manage_bindings timedout after " + timeout + "ms.");

        return resultHolder;

    }

    /**
     * @param vmProxy the virtual machine on which to execute the command
     * @param timeout the number of milliseconds to spend on this command before a TimeoutException is thrown (use -1 to wait indefinately)
     * @return the result of the command
     * @throws TimeoutException is thrown when the command takes longer than the provided timeout in milliseconds
     */
    public static ResultHolder<Object> terminateVm(final VmProxy vmProxy, final long timeout) throws TimeoutException {
        final Object monitor = new Object();
        final ResultHolder<Object> resultHolder = new ResultHolder<Object>();

        Handler<Object> resultHandler = new Handler<Object>() {
            public void handle(Object object) {
                resultHolder.setSuccess(object);
                synchronized (monitor) {
                    monitor.notify();
                }
            }
        };
        Handler<LopError> errorHandler = new Handler<LopError>() {
            public void handle(LopError lopError) {
                resultHolder.setLopError(lopError);
                synchronized (monitor) {
                    monitor.notify();
                }
            }
        };
        vmProxy.terminateVm(resultHandler, errorHandler);

        SynchronousPattern.monitorSleep(monitor, timeout);
        if (resultHolder.isEmpty())
            throw new TimeoutException("terminate_vm timedout after " + timeout + "ms.");

        return resultHolder;
    }
}
