package org.nustaq.kontraktor.services;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.nustaq.kontraktor.util.Log;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Created by ruedi on 13/08/15.
 */
public class ServiceArgs {

    @Parameter(names={"-s","-servicereg"}, description = "serviceregistry host")
    String registryHost = "localhost";

    @Parameter(names={"-sp","-serviceregport"}, description = "serviceregistry port")
    int registry = 4567;

    @Parameter(names = {"-h","-help","-?", "--help"}, help = true, description = "display help")
    boolean help;

    @Parameter(names = {"-host"}, help = true, description = "host address/name of this service")
    private String host = "localhost";

    @Parameter(names = {"-dsPortBase"}, help = true, description = "port of data shard 0. port(shard_X) = portBase + X")
    private int dataShardPortBase = 30000;

    @Parameter(names = {"-nolog"}, help = true, description = "log to sysout without log4j", arity = 1)
    public boolean asyncLog = true;

    @Parameter(names = {"-monitorhost"}, help = true, description = "monitoring api host address/name of this service")
    private String monhost = "localhost";

    @Parameter(names = {"-monitorport"}, help = true, description = "monitoring api port of this service")
    private int monport = 1113;

    public ServiceArgs() {
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            Log.Warn(this,e);
        }
    }

    public String getMonhost() {
        return monhost;
    }

    public int getMonport() {
        return monport;
    }

    public String getRegistryHost() {
        return registryHost;
    }

    public int getRegistryPort() {
        return registry;
    }

    public int getDataShardPortBase() {
        return dataShardPortBase;
    }

    public boolean isHelp() {
        return help;
    }

    @Override
    public String toString() {
        return "ServiceArgs{" +
                   "registryHost='" + registryHost + '\'' +
                   ", registry=" + registry +
                   ", help=" + help +
                   ", host='" + host + '\'' +
                   ", dataShardPortBase=" + dataShardPortBase +
                   '}';
    }

    public String getHost() {
        return host;
    }

    public boolean isAsyncLog() {
        return asyncLog;
    }

    public static ServiceArgs parseCommandLine(String[] args, ServiceArgs options) {

        JCommander com = new JCommander();
        com.addObject(options);
        try {
            com.parse(args);
        } catch (Exception ex) {
            System.out.println("command line error: '"+ex.getMessage()+"'");
            options.help = true;
        }
        if ( options.help ) {
            com.usage();
            System.exit(-1);
        }
        return options;
    }

}
