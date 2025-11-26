package apmon;

import java.util.List;
import java.util.Vector;
import java.util.logging.Logger;
import java.util.regex.*;
import java.util.stream.*;

public class MonitorJR {
    static ApMon apm = null;
    private static Logger logger = Logger.getLogger("apmon");

    public static void main(final String[] args) {
        Vector<String> addressList = new Vector<>();
        addressList.add("128.142.249.97");
        logger.severe("Added IP to list");

        try {
           // apm = new ApMon(addressList);
            logger.severe("APMon started");

            Pattern regex = Pattern.compile(".*JobRunner.*");

            List<Long> pids = ProcessHandle.allProcesses()
                .filter(p -> p.info().commandLine().isPresent())
                .filter(p -> regex.matcher(p.info().commandLine().get()).matches())
                .map(ProcessHandle::pid).collect(Collectors.toList());
            

            int JRPid = Math.toIntExact(pids.get(0));
                
            logger.severe("Setting PID " + JRPid);

            final MonitoredJob job = new MonitoredJob(JRPid, "/extra/scratch/workdir", "ALIEN_alien.site.JobRunner_Nodes", "DraculaSmaps", 640);

            long totalValue = 0;
            int maxC = 10;
            for (int i = 1; i < maxC; i++) {
                long preTs = System.currentTimeMillis();
                job.readJobInfo(false);
                long currentTs = System.currentTimeMillis();
                long value = currentTs - preTs;
                totalValue += value;
                logger.severe("Adding " + value);
                Thread.sleep(60000);    
            }
            logger.severe("Avg measure time =  " + totalValue/maxC);

        } catch (Exception e) {
            logger.severe("Error initializing ApMon: " + e);
            System.exit(-1);    
        }    
    }
}
