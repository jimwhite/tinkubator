/*
 * Copyright (c) 2009. The LoPSideD implementation of the Linked Process
 * protocol is an open-source project founded at the Center for Nonlinear Studies
 * at the Los Alamos National Laboratory in Los Alamos, New Mexico. Please visit
 * http://linkedprocess.org and LICENSE.txt for more information.
 */

package org.linkedprocess.farm.os;

import org.linkedprocess.LinkedProcess;
import org.linkedprocess.farm.LinkedProcessFarm;
import org.linkedprocess.farm.os.Job;
import org.linkedprocess.farm.os.VmBindings;
import org.linkedprocess.farm.os.JobResult;
import org.linkedprocess.farm.os.errors.*;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import java.util.*;
import java.util.logging.Logger;

/**
 * Author: josh
 * Date: Jun 24, 2009
 * Time: 2:15:27 PM
 */
public class VmScheduler {
    private static final Logger LOGGER
            = LinkedProcess.getLogger(VmScheduler.class);

    public static final int MAX_VM;
    private static final long VM_TIMEOUT;
    private static final long SCHEDULER_CLEANUP_INTERVAL;
    private static final long WAIT_UNTIL_FINISHED_INTERVAL = 100;

    static {
        Properties props = LinkedProcess.getConfiguration();

        MAX_VM = new Integer(props.getProperty(
                LinkedProcess.MAX_CONCURRENT_VIRTUAL_MACHINES_PROPERTY));
        VM_TIMEOUT = new Long(props.getProperty(
                LinkedProcess.VIRTUAL_MACHINE_TIME_TO_LIVE_PROPERTY));
        SCHEDULER_CLEANUP_INTERVAL = new Long(props.getProperty(
                LinkedProcess.SCHEDULER_CLEANUP_INTERVAL_PROPERTY));
    }

    private final SimpleBlockingQueue<VmWorker> workerQueue;
    private final Map<String, VmWorker> workersByJID;
    private final VmResultHandler resultHandler;
    private LopStatusEventHandler eventHandler;
    private final int numberOfSequencers;
    private LinkedProcess.FarmStatus status;
    private long lastCleanupTime = System.currentTimeMillis();

    private long jobsReceived = 0;
    private long jobsCompleted = 0;

    /**
     * Creates a new virtual machine scheduler.
     *
     * @param resultHandler a handler for results produced by the scheduler
     * @param eventHandler  a handler for status events generated by the scheduler
     */
    public VmScheduler(final VmResultHandler resultHandler,
                       final LopStatusEventHandler eventHandler) {
        LOGGER.info("instantiating scheduler");

        this.resultHandler = new ResultCounter(resultHandler);
        this.eventHandler = eventHandler;

        Properties conf = LinkedProcess.getConfiguration();

        long timeSlice = new Long(conf.getProperty(
                LinkedProcess.ROUND_ROBIN_QUANTUM_PROPERTY));

        workerQueue = new SimpleBlockingQueue<VmWorker>();
        workersByJID = new HashMap<String, VmWorker>();

        // A single source for workers.
        VmSequencerHelper source = createSequencerHelper();

        numberOfSequencers = new Integer(conf.getProperty(
                LinkedProcess.CONCURRENT_WORKER_THREADS_PROPERTY));

        // Note: if numberOfSequencers is less than 1, strange things may happen.
        for (int i = 0; i < numberOfSequencers; i++) {
            new VmSequencer(source, timeSlice);
        }

        setSchedulerStatus(LinkedProcess.FarmStatus.ACTIVE);
    }

    public synchronized void setStatusEventHandler(LopStatusEventHandler statusHandler) {
        this.eventHandler = statusHandler;
    }

    /**
     * Adds a job to the queue of the given machine.
     *
     * @param machineJID the JID of the virtual machine to execute the job
     * @param job        the job to execute
     * @throws org.linkedprocess.farm.os.errors.VmIsFullException
     *          if the VM in question has a full queue
     * @throws org.linkedprocess.farm.os.errors.VmNotFoundException
     *          if no such VM exists
     * @throws org.linkedprocess.farm.os.errors.JobAlreadyExistsException
     *          if a job with the given ID already exists on the machine with the given ID
     */
    public synchronized void submitJob(final String machineJID,
                                       final Job job) throws VmIsFullException, VmNotFoundException, JobAlreadyExistsException {
        if (LinkedProcess.FarmStatus.INACTIVE == status) {
            throw new IllegalStateException("scheduler has been terminated");
        }

        jobsReceived++;

        VmWorker w = getWorkerByJID(machineJID);

        // FIXME: this call may block for as long as one timeslice.
        //        This wait could probably be eliminated.
        if (!w.submitJob(job)) {
            throw new VmIsFullException(machineJID);
        }

        enqueueWorker(w);

        cleanup();
    }

    /**
     * Removes or cancels a job.
     *
     * @param machineJID the machine who was to have received the job
     * @param jobID      the ID of the specific job to be removed
     * @throws org.linkedprocess.farm.os.errors.JobNotFoundException
     *          if no job with the specified ID exists
     * @throws org.linkedprocess.farm.os.errors.VmNotFoundException
     *          if no VM worker with the specified JID exists
     */
    public synchronized void abortJob(final String machineJID,
                                      final String jobID) throws VmNotFoundException, JobNotFoundException {
        if (LinkedProcess.FarmStatus.INACTIVE == status) {
            throw new IllegalStateException("scheduler has been terminated");
        }

        VmWorker w = getWorkerByJID(machineJID);

        // FIXME: this call may block for as long as one timeslice.
        //        This wait could probably be eliminated.
        w.abortJob(jobID);

        cleanup();
    }

    /**
     * Creates a new virtual machine.
     *
     * @param vmId the intended JID of the virtual machine
     * @param language   the type of virtual machine to create
     * @throws org.linkedprocess.farm.os.errors.UnsupportedScriptEngineException
     *          if the given script engine is not supported
     * @throws org.linkedprocess.farm.os.errors.VmAlreadyExistsException
     *          if a VM with the given JID already exists in this scheduler
     * @throws org.linkedprocess.farm.os.errors.VmSchedulerIsFullException
     *          if the scheduler cannot create additional virtual machines
     */
    public synchronized void spawnVirtualMachine(final String vmId,
                                                 final String language) throws VmAlreadyExistsException, UnsupportedScriptEngineException, VmSchedulerIsFullException {
        if (LinkedProcess.FarmStatus.INACTIVE == status) {
            throw new IllegalStateException("scheduler has been terminated");
        }

        LOGGER.info("attempting to add machine of type " + language + " with JID '" + vmId + "'");

        if (LinkedProcess.FarmStatus.ACTIVE_FULL == status) {
            throw new VmSchedulerIsFullException();
        }

        if (null == vmId || 0 == vmId.length()) {
            throw new IllegalArgumentException("null or empty machine ID");
        }

        if (null == language || 0 == language.length()) {
            throw new IllegalArgumentException("non-null, non-empty language is required");
        }

        if (null != workersByJID.get(vmId)) {
            throw new VmAlreadyExistsException(vmId);
        }

        // Pick an engine based on language name, not engine name.
        // Note: language selection is case-insensitive.
        ScriptEngine engine = null;
        String l = language.toLowerCase();
        for (ScriptEngineFactory f : LinkedProcessFarm.getSupportedScriptEngineFactories()) {
            if (f.getLanguageName().toLowerCase().equals(l)) {
                engine = f.getScriptEngine();
                break;
            }
        }
        if (null == engine) {
            throw new UnsupportedScriptEngineException(language);
        }

        VmWorker w = new VmWorker(engine, resultHandler);

        workersByJID.put(vmId, w);
        if (MAX_VM == workersByJID.size()) {
            setSchedulerStatus(LinkedProcess.FarmStatus.ACTIVE_FULL);
        }

        setVirtualMachineStatus(vmId, LinkedProcess.VmStatus.ACTIVE);

        cleanup();
    }

    /**
     * Destroys an already-created virtual machine.
     *
     * @param vmId the id of the virtual machine to destroy
     * @throws org.linkedprocess.farm.os.errors.VmNotFoundException
     *          if a VM worker with the JID does not exist
     */
    public synchronized void terminateVm(final String vmId) throws VmNotFoundException {
        if (LinkedProcess.FarmStatus.INACTIVE == status) {
            throw new IllegalStateException("scheduler has been terminated");
        }

        LOGGER.fine("removing vm with vm_id '" + vmId + "'");
        VmWorker w = getWorkerByJID(vmId);

        workersByJID.remove(vmId);
        workerQueue.remove(w);

        w.terminate();
        setVirtualMachineStatus(vmId, LinkedProcess.VmStatus.NOT_FOUND);
        

        if (MAX_VM > workersByJID.size() && this.status != LinkedProcess.FarmStatus.ACTIVE) {
            setSchedulerStatus(LinkedProcess.FarmStatus.ACTIVE);
        }

        cleanup();
    }

    /**
     * @param machineJID the JID of the virtual machine to query
     * @return the set of all variable bindings in the given virtual machine
     * @throws org.linkedprocess.farm.os.errors.VmNotFoundException if no VM worker with the given JID exists
     */
    public synchronized VmBindings getAllBindings(final String machineJID) throws VmNotFoundException {
        if (LinkedProcess.FarmStatus.INACTIVE == status) {
            throw new IllegalStateException("scheduler has been terminated");
        }

        VmWorker w = getWorkerByJID(machineJID);

        return w.getAllBindings();
    }

    /**
     * @param machineJID   the JID of the virtual machine to query
     * @param bindingNames the names to bind
     * @return the bindings of the given variable names in the given virtual machine
     * @throws org.linkedprocess.farm.os.errors.VmNotFoundException if no VM worker with the given JID exists
     */
    public synchronized VmBindings getBindings(final String machineJID,
                                               final Set<String> bindingNames) throws VmNotFoundException {
        if (LinkedProcess.FarmStatus.INACTIVE == status) {
            throw new IllegalStateException("scheduler has been terminated");
        }

        VmWorker w = getWorkerByJID(machineJID);

        return w.getBindings(bindingNames);
    }

    /**
     * @return the status of this scheduler
     */
    public synchronized LinkedProcess.FarmStatus getSchedulerStatus() {
        return status;
    }

    /**
     * @param machineJID the JID of the virtual machine of interest
     * @return the status of the given virtual machine
     */
    public synchronized LinkedProcess.VmStatus getVirtualMachineStatus(final String machineJID) {
        VmWorker w = workersByJID.get(machineJID);
        return (null == w)
                ? LinkedProcess.VmStatus.NOT_FOUND
                : LinkedProcess.VmStatus.ACTIVE;
    }

    /**
     * @param machineJID the JID of the machine to execute the job
     * @param jobID      the ID of the job of interest
     * @return the status of the given job
     * @throws org.linkedprocess.farm.os.errors.VmNotFoundException
     *          if no VM worker with the given JID exists
     * @throws org.linkedprocess.farm.os.errors.JobNotFoundException
     *          if no job with the given ID exists
     */
    public synchronized LinkedProcess.JobStatus getJobStatus(final String machineJID,
                                                             final String jobID) throws VmNotFoundException, JobNotFoundException {
        VmWorker w = workersByJID.get(machineJID);

        if (null == w) {
            throw new VmNotFoundException(machineJID);
        }

        if (w.jobExists(jobID)) {
            return LinkedProcess.JobStatus.IN_PROGRESS;
        } else {
            throw new JobNotFoundException(jobID);
        }
    }

    /**
     * Shuts down all active virtual machines and cancels all jobs.
     */
    public synchronized void shutdown() {
        LOGGER.info("shutting down VMScheduler");

        workerQueue.clear();
        LOGGER.info("1");
        for (int i = 0; i < numberOfSequencers; i++) {
            // Add sentinel values to the queue, which will be retrieved by the
            // sequencers and cause them to terminate.  A null value cannot be
            // used, due to the specification of BlockingQueue.
            workerQueue.offer(VmWorker.SCHEDULER_TERMINATED_SENTINEL);
        }

        for (String vmId : workersByJID.keySet()) {
            VmWorker w = workersByJID.get(vmId);
            w.terminate();
            setVirtualMachineStatus(vmId, LinkedProcess.VmStatus.NOT_FOUND);

        }
        workersByJID.clear();

        setSchedulerStatus(LinkedProcess.FarmStatus.INACTIVE);
    }

    /**
     * Waits until all pending and currently executed jobs have finished.  This
     * is a convenience method (for unit tests and shutdown) which should be
     * used with caution.  Because the method is synchronized, you could wait
     * indefinitely on a job which never finishes, with no chance of terminating
     * the job.
     *
     * @throws InterruptedException if the Thread is interrupted while waiting
     */
    public synchronized void waitUntilFinished() throws InterruptedException {
        // Busy wait until the number of jobs completed catches up with the
        // number of jobs received.  Even failed jobs, cancelled jobs, and jobs
        // whose virtual machine has been terminated produce a result which is
        // counted.
        while (jobsCompleted < jobsReceived) {
            Thread.sleep(WAIT_UNTIL_FINISHED_INTERVAL);
        }
    }

    /**
     * Updates the given variable bindings of the given virtual machine
     *
     * @param machineJID the JID of the virtual machine to update
     * @param bindings   the key, value bindings to update
     * @throws org.linkedprocess.farm.os.errors.VmNotFoundException if no VM worker with the given JID exists
     */
    public synchronized void setBindings(final String machineJID,
                                         final VmBindings bindings) throws VmNotFoundException {
        if (LinkedProcess.FarmStatus.INACTIVE == status) {
            throw new IllegalStateException("scheduler has been terminated");
        }

        VmWorker w = getWorkerByJID(machineJID);

        w.setBindings(bindings);
    }

    ////////////////////////////////////////////////////////////////////////////

    // Note: this method is currently called only each time the scheduler is
    //       accessed to manipulate VMs and jobs.  An idle scheduler may
    //       therefore not shut down idle VMs for some time after the timeout
    //       value.

    private void cleanup() {
        // A negative timeout indicates no timeout at all.
        if (VM_TIMEOUT < 0) {
            return;
        }

        long time = System.currentTimeMillis();

        Collection<String> toShutDown = new LinkedList<String>();
        if (time - lastCleanupTime >= SCHEDULER_CLEANUP_INTERVAL) {
            for (String jid : workersByJID.keySet()) {
                VmWorker w = workersByJID.get(jid);
                if (!w.canWork()) {
                    if (time - w.getTimeLastActive() >= VM_TIMEOUT) {
                        toShutDown.add(jid);
                    }
                }
            }

            for (String jid : toShutDown) {
                try {
                    terminateVm(jid);
                } catch (VmNotFoundException e) {
                    // Ignore this error: it means the worker has already been explicitly terminated.
                }
            }

            lastCleanupTime = time;
        }
    }

    private VmSequencerHelper createSequencerHelper() {
        return new VmSequencerHelper() {
            public VmWorker getWorker() {
                try {
                    return workerQueue.take();
                } catch (InterruptedException e) {
                    LOGGER.severe("thread interrupted unexpectedly in queue");
                    System.exit(1);
                    return null;
                }
            }

            public void putBackWorker(final VmWorker w,
                                      final boolean idle) {
                // If the worker thread died unexpectedly, terminate the worker.
                // Note: this should no longer happen, as workers attempt to recover from
                // thread death (so this code may go away at some point).
                /*
                if (VMWorker.Status.ABNORMAL_ERROR == w.status) {
                    for (String jid : workersByJID.keySet()) {
                        // This is not efficient, but it shouldn't happen often.
                        if (workersByJID.get(jid) == w) {
                            try {
                                // Note: this will not be called in the main thread which normally terminates VMs.
                                terminateVm(jid);
                            } catch (VmWorkerNotFoundException e) {
                                LOGGER.severe("there was an error terminating a failed VM worker: " + e);
                                e.printStackTrace();
                            }
                        }
                    }
                }*/

                if (!idle) {
                    enqueueWorker(w);
                }
            }
        };
    }

    private void enqueueWorker(final VmWorker w) {
        //LOGGER.info("enqueueing worker: " + w);

        // Add the worker to the queue, unless it is already present.  This
        // check prevents clients from benefitting from aggressive behavior,
        // making very frequent requests to the same VM: the scheduler is fair
        // with respect to VMs.  Note, however, that the client may simply
        // spawn more VMs for greater throughput with respect to its competitors
        // on the machine.
        workerQueue.offerDistinct(w);
        //LOGGER.info("...done (workerQueue.size() = " + workerQueue.size() + ")");
    }

    private VmWorker getWorkerByJID(final String machineJID) throws VmNotFoundException {
        VmWorker w = workersByJID.get(machineJID);

        if (null == w) {
            throw new VmNotFoundException(machineJID);
        }

        return w;
    }

    private void setSchedulerStatus(final LinkedProcess.FarmStatus newStatus) {
        status = newStatus;
        eventHandler.schedulerStatusChanged(status);
    }

    private void setVirtualMachineStatus(final String vmId,
                                         final LinkedProcess.VmStatus newStatus) {
        eventHandler.virtualMachineStatusChanged(vmId, newStatus);
    }

    ////////////////////////////////////////////////////////////////////////////

    public interface VmResultHandler {
        void handleResult(JobResult result);
    }

    public interface VmSequencerHelper {
        VmWorker getWorker();

        void putBackWorker(VmWorker w, boolean idle);
    }

    public interface LopStatusEventHandler {
        void schedulerStatusChanged(LinkedProcess.FarmStatus newStatus);
        void virtualMachineStatusChanged(String vmId, LinkedProcess.VmStatus newStatus);
    }  

    private class ResultCounter implements VmResultHandler {
        private final VmResultHandler innerHandler;
        private final Object monitor = "";

        public ResultCounter(final VmResultHandler innerHandler) {
            this.innerHandler = innerHandler;
        }

        public void handleResult(JobResult result) {
            try {
                innerHandler.handleResult(result);
            } finally {
                // For the sake of waitUntilFinished, count the job as completed
                // AFTER the call to the inner handler has completed (or failed).
                synchronized (monitor) {
                    jobsCompleted++;
                }
            }
        }
    }
}
