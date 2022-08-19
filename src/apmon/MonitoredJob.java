/*
 * ApMon - Application Monitoring Tool
 * Version: 2.2.9
 *
 * Copyright (C) 2006 - 2010 California Institute of Technology
 *
 * Permission is hereby granted, free of charge, to use, copy and modify
 * this software and its documentation (the "Software") for any
 * purpose, provided that existing copyright notices are retained in
 * all copies and that this notice is included verbatim in any distributions
 * or substantial portions of the Software.
 * This software is a part of the MonALISA framework (http://monalisa.caltech.edu).
 * Users of the Software are asked to feed back problems, benefits,
 * and/or suggestions about the software to the MonALISA Development Team
 * (MonALISA-CIT@cern.ch). Support for this software - fixing of bugs,
 * incorporation of new features - is done on a best effort basis. All bug
 * fixes and enhancements will be made available under the same terms and
 * conditions as the original software,
 *
 * IN NO EVENT SHALL THE AUTHORS OR DISTRIBUTORS BE LIABLE TO ANY PARTY FOR
 * DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES ARISING OUT
 * OF THE USE OF THIS SOFTWARE, ITS DOCUMENTATION, OR ANY DERIVATIVES THEREOF,
 * EVEN IF THE AUTHORS HAVE BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * THE AUTHORS AND DISTRIBUTORS SPECIFICALLY DISCLAIM ANY WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE, AND NON-INFRINGEMENT. THIS SOFTWARE IS
 * PROVIDED ON AN "AS IS" BASIS, AND THE AUTHORS AND DISTRIBUTORS HAVE NO
 * OBLIGATION TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 * MODIFICATIONS.
 */

package apmon;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import apmon.lisa_host.cmdExec;

/**
 * @author ML team
 */
public class MonitoredJob implements AutoCloseable {
	/**
	 * process id
	 */
	final int pid;

	/**
	 * process id of the payload
	 */
	int payloadPid;

	/*
	 * process id of the job wrapper
	 */
	int wrapperPid;

	/** the job's working directory */
	final String workDir;

	/** the cluster name that will be included in the monitoring datagrams */
	final String clusterName;

	/** the node name that will be included in the monitoring datagrams */
	final String nodeName;

	private static final Logger logger = Logger.getLogger("apmon");

	private cmdExec exec = null;

	final int numCPUs;

	final boolean isLinux;

	final HashMap<Integer, Double> currentProcCPUTime;
	final HashMap<String, Double> voluntaryCS;
	final HashMap<String, Double> nonvoluntaryCS;
	double totalCPUTime;
	long previousMeasureTime;
	double totalVoluntaryContextSwitches;
	double totalNonVoluntaryContextSwitches;
	final HashMap<String, Double> countStats;

	double cpuEfficiency;
	double instantCpuEfficiency;
	int hertz;

	boolean payloadMonitoring;

	final HashMap<String, Double> commandVoluntaryCS;
	final HashMap<String, Double> commandNonvoluntaryCS;
	final HashMap<Integer, String> procCommands;
	final HashMap<String, Double> currentCommandCPUTime;
	final HashMap<String, Double> instantCommandCPUEfficiency;

	/**
	 * Synchronize updates
	 */
	protected static final Object requestSync = new Object();

	/**
	 * @param _pid
	 * @param _workDir
	 * @param _clusterName
	 * @param _nodeName
	 */
	public MonitoredJob(int _pid, String _workDir, String _clusterName, String _nodeName) {
		this(_pid, _workDir, _clusterName, _nodeName, 1);
	}

	/**
	 * @param _pid
	 * @param _workDir
	 * @param _clusterName
	 * @param _nodeName
	 * @param _numCPUs
	 */
	public MonitoredJob(int _pid, String _workDir, String _clusterName, String _nodeName, int _numCPUs) {
		this.pid = _pid;
		this.workDir = _workDir;
		this.clusterName = _clusterName;
		this.nodeName = _nodeName;
		this.exec = new cmdExec();
		this.numCPUs = _numCPUs;
		File f = new File("/proc/stat");
		if (f.exists() && f.canRead()) {
			this.isLinux = true;
			try {
				this.hertz = Integer.parseInt(exec.executeCommandReality("getconf CLK_TCK", "").replace("\n", ""));
			} catch (NumberFormatException e) {
				this.hertz = 100;
				logger.log(Level.SEVERE, "Could not get CLK_TCK variable from the system. Assigning a default 100 Hz. \n" + e);
			}
		} else
			this.isLinux = false;
		this.currentProcCPUTime = new HashMap<>();
		this.voluntaryCS = new HashMap<>();
		this.nonvoluntaryCS = new HashMap<>();
		this.previousMeasureTime = 0;
		this.payloadMonitoring = false;
		this.countStats = new HashMap<>();
		this.commandVoluntaryCS = new HashMap<>();
		this.commandNonvoluntaryCS = new HashMap<>();
		this.procCommands = new HashMap<>();
		this.currentCommandCPUTime = new HashMap<>();
		this.instantCommandCPUEfficiency = new HashMap<>();
	}

	/**
	 * @return monitored process id
	 */
	public int getPid() {
		return pid;
	}

	/**
	 * @return disk usage
	 */
	public HashMap<Long, Double> readJobDiskUsage() {
		HashMap<Long, Double> hm = new HashMap<>();
		String cmd = null, aux = null, result = null;
		double workdir_size = 0.0, disk_total = 0.0, disk_used = 0.0, disk_free = 0.0, disk_usage = 0.0;

		if (workDir == null)
			return null;

		final String safeWorkDir = workDir.replace("'", "'\\''");

		cmd = "POSIXLY_CORRECT=1 find -H '" + safeWorkDir + "' -xdev -ls | awk '$4 == 1 || $3 ~ /^d/ || ! c[$1]++ { s += $2 } END { print int(s / 2) }'";
		result = exec.executeCommandReality(cmd, "");

		try {
			workdir_size = Double.parseDouble(result) / 1024;
		}
		catch (NumberFormatException nfe) {
			if (logger.isLoggable(Level.WARNING))
				logger.log(Level.WARNING, "Exception parsing the output of `" + cmd + "`", nfe);

			cmd = "du -Lscm '" + safeWorkDir + "' | tail -1 | cut -f 1";
			result = exec.executeCommandReality(cmd, "");
			
			try {
				workdir_size = Double.parseDouble(result);
			}
			catch (NumberFormatException nfe2) {
				if (logger.isLoggable(Level.WARNING))
					logger.log(Level.WARNING, "Exception parsing the output of `" + cmd + "`", nfe2);
			}
		}

		hm.put(ApMonMonitoringConstants.LJOB_WORKDIR_SIZE, Double.valueOf(workdir_size));

		cmd = "df -P -m '" + safeWorkDir + "' | tail -1";
		result = exec.executeCommand(cmd, "");
		final StringTokenizer st = new StringTokenizer(result, " \t%");

		st.nextToken(); // skip over the filesystem name

		aux = st.nextToken();
		disk_total = Double.parseDouble(aux);
		hm.put(ApMonMonitoringConstants.LJOB_DISK_TOTAL, Double.valueOf(disk_total));

		aux = st.nextToken();
		disk_used = Double.parseDouble(aux);
		hm.put(ApMonMonitoringConstants.LJOB_DISK_USED, Double.valueOf(disk_used));

		aux = st.nextToken();
		disk_free = Double.parseDouble(aux);
		hm.put(ApMonMonitoringConstants.LJOB_DISK_FREE, Double.valueOf(disk_free));

		aux = st.nextToken();
		disk_usage = Double.parseDouble(aux);
		hm.put(ApMonMonitoringConstants.LJOB_DISK_USAGE, Double.valueOf(disk_usage));

		return hm;
	}

	/**
	 * @param targetPid
	 * @return children processes
	 */
	private Vector<Integer> getChildren(int targetPid) {
		Vector<Integer> pids, ppids, children = null;
		String cmd = null, result = null;
		int nProcesses = 0, nChildren = 1;
		int i, j;

		cmd = "ps -eo ppid,pid";
		result = exec.executeCommandReality(cmd, "");
		boolean pidFound = false;
		if (result == null) {
			logger.warning("The child processes could not be determined");
			return null;
		}

		StringTokenizer st = new StringTokenizer(result, " \n");
		nProcesses = st.countTokens() / 2 - 1;

		try {
			// Skip over the header line with its two tokens
			st.nextToken();
			st.nextToken();

			pids = new Vector<>();
			ppids = new Vector<>();
			children = new Vector<>();
			children.add(Integer.valueOf(targetPid));
			while (st.hasMoreTokens()) {
				i = Integer.parseInt(st.nextToken());
				j = Integer.parseInt(st.nextToken());
				if (j == targetPid)
					pidFound = true;
				ppids.add(Integer.valueOf(i));
				pids.add(Integer.valueOf(j));
				if (i == (children.elementAt(0)).intValue()) {
					children.add(Integer.valueOf(j));
					nChildren++;
				}
			}
			if (!pidFound)
				return null;

			i = 1;

			while (i < nChildren) {
				/* find the children of the i-th child */
				for (j = 0; j < nProcesses; j++) {
					if (ppids.elementAt(j).equals(children.elementAt(i))) {
						children.add(pids.elementAt(j));
						nChildren++;
					}
				}
				i++;
			}
		} catch (NoSuchElementException e) {
			logger.log(Level.INFO, "Could not parse contents of `ps -eo ppid,pid` command \n" + e);
		}

		return children;
	}

	/**
	 * @param s
	 * @return parsed time
	 */
	public static long parsePSTime(String s) {
		long days, hours, mins, secs;
		if (s.indexOf('-') > 0) {
			StringTokenizer st = new StringTokenizer(s, "-:.");
			days = Long.parseLong(st.nextToken());
			hours = Long.parseLong(st.nextToken());
			mins = Long.parseLong(st.nextToken());
			secs = Long.parseLong(st.nextToken());
			return 24 * 3600 * days + 3600 * hours + 60 * mins + secs;
		}
		if (s.indexOf(':') > 0 && s.indexOf(':') != s.lastIndexOf(':')) {
			StringTokenizer st = new StringTokenizer(s, ":.");
			hours = Long.parseLong(st.nextToken());
			mins = Long.parseLong(st.nextToken());
			secs = Long.parseLong(st.nextToken());
			return 3600 * hours + 60 * mins + secs;
		}

		if (s.indexOf(':') > 0) {
			StringTokenizer st = new StringTokenizer(s, ":.");
			mins = Long.parseLong(st.nextToken());
			secs = Long.parseLong(st.nextToken());
			return 60 * mins + secs;
		}

		return -1;
	}

	/**
	 * @return job monitoring
	 * @throws IOException
	 */
	public HashMap<Long, Double> readJobInfo() throws IOException {
		HashMap<Long, Double> ret = new HashMap<>();
		String result = null;
		String line = null;

		int i;

		double rsz = 0.0, vsz = 0.0;
		double etime = 0.0;
		double pmem = 0.0;

		double _rsz, _vsz;
		double _etime, _cputime;
		double _pcpu, _pmem;

		long apid, fd = 0;

		double elapsedtime = 0.0;

		if (!isLinux) {
			totalCPUTime = 0.0;
			cpuEfficiency = 0.0;
		}

		/*
		 * this list contains strings of the form "rsz_vsz_command" for every pid; it is used to avoid adding several times processes that have multiple threads and appear in ps as
		 * separate processes, occupying exactly the same amount of memory and having the same command name. For every line from the output of the ps command we verify if the
		 * rsz_vsz_command combination is already in the list.
		 */
		Vector<String> mem_cmd_list = new Vector<>();

		synchronized (requestSync) {
			/* get the list of the process' descendants */
			final Vector<Integer> children = getChildren(pid);
	
			if (children == null)
				return null;
	
			logger.fine("Number of children for process " + pid + ": " + children.size());
	
			/* issue the "ps" command to obtain information on all the descendants */
			final StringBuilder cmd = new StringBuilder("ps -p ");
			for (i = 0; i < children.size(); i++) {
				if (i > 0)
					cmd.append(',');
				cmd.append(children.elementAt(i));
			}
	
			if (isLinux)
				cmd.append(" -o pid,etime,%mem,rss,vsz,comm");
			else
				cmd.append(" -o pid,etime,time,%cpu,%mem,rss,vsz,comm");
			result = exec.executeCommandReality(cmd.toString(), "");
	
			// skip over the first line of the `ps` output
			int idx = result.indexOf('\n');
	
			if (idx > 0)
				result = result.substring(idx + 1);
		
			double previousTotalCPUTime = totalCPUTime;
			long currentMeasureTime = System.currentTimeMillis();
			StringTokenizer rst = new StringTokenizer(result, "\n");
			while (rst.hasMoreTokens()) {
				line = rst.nextToken();
				try {
					StringTokenizer st = new StringTokenizer(line, " \t");

					apid = Long.parseLong(st.nextToken());
					_etime = parsePSTime(st.nextToken());
					if (pid == apid)
						elapsedtime = _etime;

					if (!isLinux) {
						_cputime = parsePSTime(st.nextToken());
						totalCPUTime += _cputime;
						_pcpu = Double.parseDouble(st.nextToken());
						cpuEfficiency += _pcpu;
					}
					_pmem = Double.parseDouble(st.nextToken());
					_rsz = Double.parseDouble(st.nextToken());
					_vsz = Double.parseDouble(st.nextToken());
					String cmdName = st.nextToken();

					etime = etime > _etime ? etime : _etime;

					String mem_cmd_s = "" + _rsz + "_" + _vsz + "_" + cmdName;
					// mem_cmd_list.add(mem_cmd_s);
					if (mem_cmd_list.indexOf(mem_cmd_s) == -1) {
						pmem += _pmem;
						vsz += _vsz;
						rsz += _rsz;
						mem_cmd_list.add(mem_cmd_s);
						long _fd = countOpenFD(apid);
						if (_fd != -1)
							fd += _fd;
					}
				}
				catch (final Exception e) {
					System.err.println("Exception parsing line `" + line + "` of the output of `" + cmd + "`: " + e.getMessage());
					e.printStackTrace();
				}
			}

			if (isLinux)
				getCpuEfficiency(children, elapsedtime, previousTotalCPUTime);
			else {
				if (previousMeasureTime > 0 && (totalCPUTime - previousTotalCPUTime) > 0)
					instantCpuEfficiency =  100000 * (totalCPUTime - previousTotalCPUTime) / (currentMeasureTime - previousMeasureTime);
				else
					instantCpuEfficiency = 0;
			}

			double pssKB = 0;
			double swapPssKB = 0;
			for (final Integer child : children) {
				try {
					final String content = Files.readString(Path.of("/proc/" + child + "/smaps"));

					try (BufferedReader br = new BufferedReader(new StringReader(content))) {
						String s;

						while ((s = br.readLine()) != null) {
							// File content is something like this (the keys that we are missing from the `ps` output only):
							// Pss: 16 kB
							// SwapPss: 0 kB

							if (s.length()<8)
								continue;
							
							final char c0 = s.charAt(0);

							if ((c0 == 'P' && s.startsWith("Pss:")) || (c0 == 'S' && s.startsWith("SwapPss:"))) {
								final int idxLast = s.lastIndexOf(' ');
								final int idxPrev = s.lastIndexOf(' ', idxLast - 1);

								if (idxPrev > 0 && idxLast > 0) {
									final long value = Long.parseLong(s.substring(idxPrev + 1, idxLast));

									if (c0 == 'S')
										swapPssKB += value;
									else
										pssKB += value;
								}
							}
						}
					}
				}
				catch (@SuppressWarnings("unused") final IOException | IllegalArgumentException e) {
					// ignore
				}
			}

			ret.put(ApMonMonitoringConstants.LJOB_RUN_TIME, Double.valueOf(elapsedtime * numCPUs));
			if (isLinux)
				ret.put(ApMonMonitoringConstants.LJOB_CPU_TIME, Double.valueOf(totalCPUTime / hertz));
			else
				ret.put(ApMonMonitoringConstants.LJOB_CPU_TIME, Double.valueOf(totalCPUTime));
			ret.put(ApMonMonitoringConstants.LJOB_CPU_USAGE, Double.valueOf(cpuEfficiency));
			ret.put(ApMonMonitoringConstants.LJOB_INSTANT_CPU_USAGE, Double.valueOf(instantCpuEfficiency));
			ret.put(ApMonMonitoringConstants.LJOB_MEM_USAGE, Double.valueOf(pmem));
			ret.put(ApMonMonitoringConstants.LJOB_RSS, Double.valueOf(rsz));
			ret.put(ApMonMonitoringConstants.LJOB_VIRTUALMEM, Double.valueOf(vsz));
			ret.put(ApMonMonitoringConstants.LJOB_OPEN_FILES, Double.valueOf(fd));

			if (pssKB == 0 && rsz > 0) {
				// fake the PSS values if they are not supported, assuming that
				// PSS == RSS and SwapPSS = Virtual - RSS

				pssKB = rsz;
				swapPssKB = vsz - rsz;
			}

			ret.put(ApMonMonitoringConstants.LJOB_PSS, Double.valueOf(pssKB));
			ret.put(ApMonMonitoringConstants.LJOB_SWAPPSS, Double.valueOf(swapPssKB));

			if (payloadMonitoring == true && payloadPid != 0)
				readJobInfoExtraParams();

			previousMeasureTime = currentMeasureTime;
		}
		return ret;
	}

	/**
	 * @param payloadGrep Pattern to grep for finding the payload
	 */
	public void discoverPayloadPid(String payloadGrep) {
		if (payloadMonitoring == true && payloadPid == 0 && wrapperPid != 0) {
			String cmd = "pgrep -P " + wrapperPid + " -f " + payloadGrep;
			String result = exec.executeCommandReality(cmd, "");
			if (!result.isEmpty()) {
				payloadPid = Integer.parseInt(result.replace("\n", ""));
				logger.log(Level.INFO, "PID of payload has been set to " + payloadPid);
			}
		}
	}

	/**
	 *
	 */
	public void readJobInfoExtraParams() {
		HashMap<String, Double> processStatus = new HashMap<>();
		HashMap<String, Double> threadStatus = new HashMap<>();
		int thread_count = 0;
		long currentMeasureTime = System.currentTimeMillis();
		HashMap<String, Double> previousVoluntaryCS = new HashMap<>(voluntaryCS);
		voluntaryCS.clear();
		HashMap<String, Double> previousNonVoluntaryCS = new HashMap<>(nonvoluntaryCS);
		nonvoluntaryCS.clear();
		double previousVoluntaryTotalContextSwitches = totalVoluntaryContextSwitches;
		double previousNonVoluntaryTotalContextSwitches = totalNonVoluntaryContextSwitches;

		/* get the list of the process' descendants */
		Vector<Integer> children = getChildren(payloadPid);

		HashMap<String, Double> cs;
		if (children != null) {
			for (Integer child : children) {
				checkRegisteredCommand(child);
				cs = registerStatusAndCountCSwitch(processStatus, "/proc/" + child + "/status");
				if (cs.containsKey("voluntary")) {
					double newCS = cs.get("voluntary").doubleValue()
							- previousVoluntaryCS.getOrDefault(child.toString(), Double.valueOf(0)).doubleValue();
					totalVoluntaryContextSwitches = totalVoluntaryContextSwitches + newCS;
					voluntaryCS.put(child.toString(), cs.get("voluntary"));

					String command = procCommands.get(child);
					if (command != null)
						commandVoluntaryCS.put(command,  Double.valueOf(commandVoluntaryCS.getOrDefault(command, Double.valueOf(0)).doubleValue() + newCS));
				}
				if (cs.containsKey("nonvoluntary")) {
					double newCS = cs.get("nonvoluntary").doubleValue()
							- previousNonVoluntaryCS.getOrDefault(child.toString(), Double.valueOf(0)).doubleValue();
					totalNonVoluntaryContextSwitches = totalNonVoluntaryContextSwitches + newCS;
					nonvoluntaryCS.put(child.toString(), cs.get("nonvoluntary"));
					String command = procCommands.get(child);
					if (command != null)
						commandNonvoluntaryCS.put(command,  Double.valueOf(commandNonvoluntaryCS.getOrDefault(command, Double.valueOf(0)).doubleValue() + newCS));
				}

				ArrayList<Integer> threads = new ArrayList<>();
				File threadsDir = new File("/proc/" + child + "/task");
				File[] threadIds = threadsDir.listFiles();
				if (threadIds != null) {
					for (File threadId : threadIds) {
						if (threadId.isDirectory()) {
							try {
								threads.add(Integer.valueOf(threadId.getName()));
							}
							catch (NumberFormatException e) {
								logger.log(Level.WARNING, "Some of the /proc/" + child
										+ "/task directory names did not have the correct formatting. \n" + e);
							}
						}
					}
				}
				for (Integer thread : threads) {
					if (thread != child) {
						checkRegisteredCommand(thread);
						cs = registerStatusAndCountCSwitch(threadStatus,
								"/proc/" + child + "/task/" + thread + "/status");

						if (cs.containsKey("voluntary")) {
							double newCS = cs.get("voluntary").doubleValue()
							- previousVoluntaryCS
									.getOrDefault(child.toString() + "-" + thread.toString(), Double.valueOf(0))
									.doubleValue();
							totalVoluntaryContextSwitches = totalVoluntaryContextSwitches + newCS;
							voluntaryCS.put(child.toString() + "-" + thread.toString(), cs.get("voluntary"));
							String command = procCommands.get(thread);
							if (command != null)
								commandVoluntaryCS.put(command,  Double.valueOf(commandVoluntaryCS.getOrDefault(command, Double.valueOf(0)).doubleValue() + newCS));
						}
						if (cs.containsKey("nonvoluntary")) {
							double newCS = cs.get("nonvoluntary").doubleValue()
									- previousNonVoluntaryCS
									.getOrDefault(child.toString() + "-" + thread.toString(), Double.valueOf(0))
									.doubleValue();
							totalNonVoluntaryContextSwitches = totalNonVoluntaryContextSwitches + newCS;
							nonvoluntaryCS.put(child.toString() + "-" + thread.toString(), cs.get("nonvoluntary"));
							String command = procCommands.get(thread);
							if (command != null)
								commandNonvoluntaryCS.put(command,  Double.valueOf(commandNonvoluntaryCS.getOrDefault(command, Double.valueOf(0)).doubleValue() + newCS));
						}
					}
				}
				thread_count = thread_count + threads.size();
			}

			if (previousMeasureTime > 0) {
				long timeDiff = currentMeasureTime - previousMeasureTime;
				double voluntaryContextSwitchingRate = 1000
						* (totalVoluntaryContextSwitches - previousVoluntaryTotalContextSwitches) / timeDiff;
				double nonVoluntaryContextSwitchingRate = 1000
						* (totalNonVoluntaryContextSwitches - previousNonVoluntaryTotalContextSwitches) / timeDiff;
				countStats.put(ApMonMonitoringConstants.getJobMLParamName(ApMonMonitoringConstants.LJOB_RATE_CONTEXTSW)
						+ "_voluntary", Double.valueOf(voluntaryContextSwitchingRate));
				countStats.put(ApMonMonitoringConstants.getJobMLParamName(ApMonMonitoringConstants.LJOB_RATE_CONTEXTSW)
						+ "_nonvoluntary", Double.valueOf(nonVoluntaryContextSwitchingRate));
			}

			countStats.put(ApMonMonitoringConstants.getJobMLParamName(ApMonMonitoringConstants.LJOB_TOTAL_PROCS),
					Double.valueOf(children.size()));
			countStats.put(ApMonMonitoringConstants.getJobMLParamName(ApMonMonitoringConstants.LJOB_TOTAL_THREADS),
					Double.valueOf(thread_count));
			for (String status : threadStatus.keySet())
				countStats.put(ApMonMonitoringConstants.getJobMLParamName(ApMonMonitoringConstants.JOB_TOTAL_THREADS)
						+ "_" + status, threadStatus.get(status));
			for (String status : processStatus.keySet())
				countStats.put(ApMonMonitoringConstants.getJobMLParamName(ApMonMonitoringConstants.JOB_TOTAL_PROCS)
						+ "_" + status, processStatus.get(status));
			for (String command : commandVoluntaryCS.keySet())
				countStats.put(
						ApMonMonitoringConstants.getJobMLParamName(
								ApMonMonitoringConstants.LJOB_TOTAL_VOLUNTARY_CONTEXTSW) + "_" + command,
						commandVoluntaryCS.get(command));
			for (String command : commandNonvoluntaryCS.keySet())
				countStats.put(
						ApMonMonitoringConstants.getJobMLParamName(
								ApMonMonitoringConstants.LJOB_TOTAL_NONVOLUNTARY_CONTEXTSW) + "_" + command,
						commandNonvoluntaryCS.get(command));
			for (String command : instantCommandCPUEfficiency.keySet())
				countStats.put(ApMonMonitoringConstants.getJobMLParamName(ApMonMonitoringConstants.LJOB_CPU_USAGE) + "_"
						+ command, instantCommandCPUEfficiency.get(command));

			countStats.put(
					ApMonMonitoringConstants.getJobMLParamName(ApMonMonitoringConstants.LJOB_TOTAL_VOLUNTARY_CONTEXTSW)
							+ "_total",
					Double.valueOf(totalVoluntaryContextSwitches));
			countStats.put(
					ApMonMonitoringConstants
							.getJobMLParamName(ApMonMonitoringConstants.LJOB_TOTAL_NONVOLUNTARY_CONTEXTSW) + "_total",
					Double.valueOf(totalNonVoluntaryContextSwitches));
		} else
			logger.log(Level.INFO, "The job has no children");
	}

	private void checkRegisteredCommand(Integer procPid) {
		if (!procCommands.containsKey(procPid)) {
			File f = new File("/proc/" + procPid + "/cmdline");
			if (f.exists() && f.canRead()) {
				String s;
				try (BufferedReader br = new BufferedReader(new FileReader(f))) {
					if ((s = br.readLine()) != null) {
						s = s.split("\u0000")[0];
						if (s.contains("/cvmfs"))
							s = s.substring(s.lastIndexOf('/') + 1);
						procCommands.put(procPid, s);
					}
				} catch (IOException | IllegalArgumentException e) {
					logger.log(Level.WARNING, "The file /proc/" + pid + "/cmdline could not be accessed.\n" + e);
				}
			}
		}
	}

	/*
	 * @param statusRegistry registry of number of processes per status
	 *
	 * @param filename where to grep the contents
	 *
	 * @return Amount of voluntary and non-voluntary observed Context Switches
	 */
	private static HashMap<String, Double> registerStatusAndCountCSwitch(HashMap<String, Double> statusRegistry,
			String filename) {
		File f = new File(filename);
		String s;
		HashMap<String, Double> contextSwitches = new HashMap<>();

		if (f.exists() && f.canRead()) {
			try (BufferedReader br = new BufferedReader(new FileReader(f))) {
				while ((s = br.readLine()) != null) {
					if (s.startsWith("State")) {
						String status = s.split("\\s+")[1];
						if (status.length() == 1) {
							int statusCount = statusRegistry.getOrDefault(status, Double.valueOf(0)).intValue() + 1;
							statusRegistry.put(status, Double.valueOf(statusCount));
						}
					}
					try {
						if (s.startsWith("voluntary_ctxt_switches")) {
							double cs = Double.parseDouble(s.split("\\s+")[1]);
							contextSwitches.put("voluntary", Double.valueOf(
									contextSwitches.getOrDefault("voluntary", Double.valueOf(0)).doubleValue() + cs));
						}
						if (s.startsWith("nonvoluntary_ctxt_switches")) {
							double cs = Double.parseDouble(s.split("\\s+")[1]);
							contextSwitches.put("nonvoluntary", Double.valueOf(
									contextSwitches.getOrDefault("nonvoluntary", Double.valueOf(0)).doubleValue()
											+ cs));
						}
					} catch (NumberFormatException e) {
						logger.log(Level.WARNING,
								"The file " + filename + " does not have the correct formatting. \n" + e);
					}
				}

			} catch (IOException | IllegalArgumentException e) {
				logger.log(Level.WARNING,
						"The file " + filename + " does NOT exist or has incorrect formatting.\n" + e);
			}
		}
		return contextSwitches;
	}

	/*
	 * Computes instantaneous and average CPU efficiencies
	 *
	 * @param children
	 *
	 * @param elapsedtime of the long-lived process
	 *
	 * @param previousTotalCPUTime
	 */
	private void getCpuEfficiency(Vector<Integer> children, double elapsedtime, double previousTotalCPUTime) {
		long currentMeasureTime = System.currentTimeMillis();
		HashMap<Integer, Double> previousProcCPUTime = new HashMap<>(currentProcCPUTime);
		currentProcCPUTime.clear();
		String filename;
		for (Integer child : children) {
			filename = "/proc/" + child + "/stat";
			File f = new File(filename);

			if (f.exists() && f.canRead()) {
				try (BufferedReader br = new BufferedReader(new FileReader(f))) {
					String s;
					while ((s = br.readLine()) != null) {
						String[] splitted = s.split(" ");
						try {
							if (splitted.length >= 15) {
								final double procCpuTime = Double.parseDouble(splitted[13]) + Double.parseDouble(splitted[14]);
								currentProcCPUTime.put(child, Double.valueOf(procCpuTime));
								totalCPUTime = totalCPUTime + procCpuTime
										- previousProcCPUTime.getOrDefault(child, Double.valueOf(0)).doubleValue();
							}
						} catch (NumberFormatException e) {
							logger.log(Level.WARNING, "The " + filename
									+ " file did not have the correct formatting. Omitting process accounting.\n" + e);
						}
					}

				} catch (IOException | IllegalArgumentException e) {
					if (logger.isLoggable(Level.FINE))
						logger.log(Level.FINE, "The file " + filename + " output could not be accessed. The process might have already died.\n" + e);
				}
			} else {
				if (logger.isLoggable(Level.FINE))
					logger.log(Level.FINE, "The file " + filename + " does NOT exist");
			}
		}

		if (previousMeasureTime>0) {
			long timeDiff = currentMeasureTime - previousMeasureTime;
			registerConsumedCPUPerCommand(previousProcCPUTime, timeDiff);
	
			instantCpuEfficiency = 100000 * (((totalCPUTime - previousTotalCPUTime) / hertz) / timeDiff); // Current instantaneous efficiency
			if (instantCpuEfficiency > 1000) {
				logger.log(Level.WARNING, "Instantaneous CPU efficiency = "
						+ String.format("%.2f", Double.valueOf(instantCpuEfficiency)) + " %.");
			}
		}
		
		cpuEfficiency = 100 * (totalCPUTime / hertz) / (elapsedtime * numCPUs); // If we want to get the average
																				// efficiency
		// logger.log(Level.INFO, "Average CPU efficiency = " + String.format("%.2f", Double.valueOf(cpuEfficiency)) + " %");
	}

	private void registerConsumedCPUPerCommand(HashMap<Integer, Double> previousProcCPUTime, long timeDiff) {
		String command;
		currentCommandCPUTime.clear();
		for (Integer pidProc : currentProcCPUTime.keySet()) {
			checkRegisteredCommand(pidProc);
			command = procCommands.get(pidProc);
			if (command != null) {
				currentCommandCPUTime.put(command, currentProcCPUTime.get(pidProc));
				instantCommandCPUEfficiency.put(command,
						Double.valueOf(100000 * ((currentProcCPUTime.get(pidProc).doubleValue()
								- previousProcCPUTime.getOrDefault(pidProc, Double.valueOf(0)).doubleValue()) / hertz)
								/ timeDiff));
			}
		}
	}

	/**
	 * count the number of open files for the given pid
	 *
	 * @param processid
	 * @return opened file descriptors
	 */
	public static long countOpenFD(final long processid) {

		long open_files;
		int mypid = ApMon.getPID();
		String dir = "/proc/" + processid + "/fd";
		File f = new File(dir);
		if (f.exists()) {
			if (f.canRead()) {
				final String[] listing = f.list();
				if (listing != null) {
					open_files = listing.length - 2;

					if (processid == mypid)
						open_files -= 2;

					logger.log(Level.FINE, "Counting open_files for process " + processid);
				}
				else {
					open_files = -1;
					logger.log(Level.SEVERE, "ProcInfo: null listing of " + dir);
				}
			}
			else {
				open_files = -1;
				logger.log(Level.SEVERE, "ProcInfo: cannot count the number of opened files for job" + processid);
			}
		}
		else {
			open_files = -1;
			logger.log(Level.SEVERE, "ProcInfo: job " + processid + "not exist.");
		}
		return open_files;
	}

	@Override
	public String toString() {
		return "[" + pid + "]" + " " + workDir + " " + " " + clusterName + " " + nodeName;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(final Object obj) {
		if (obj == null)
			return false;

		if (!(obj instanceof MonitoredJob))
			return false;

		final MonitoredJob other = (MonitoredJob) obj;

		return pid == other.pid && clusterName.equals(other.clusterName) && nodeName.equals(other.nodeName);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return 13 * pid + 19 * clusterName.hashCode() + 31 * nodeName.hashCode();
	}

	@Override
	public void close() {
		if (exec != null)
			exec.close();
	}

	/**
	 * @param wrapperPid
	 */
	public void setWrapperPid(int wrapperPid) {
		this.wrapperPid = wrapperPid;
	}

	/**
	 * 
	 */
	public void setPayloadMonitoring() {
		payloadMonitoring = true;
		logger.log(Level.INFO, "Payload monitoring has been activated");
	}

	/**
	 * @return <code>true</code> if payload monitoring is enabled
	 */
	public boolean getPayloadMonitoring() {
		return payloadMonitoring;
	}
}
