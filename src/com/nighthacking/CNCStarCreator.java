package com.nighthacking;

import com.willwinder.universalgcodesender.GrblController;
import com.willwinder.universalgcodesender.listeners.ControllerListener;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.vecmath.Point3d;

/**
 * GCode CNC Star Creator
 * 
 * For more details check out the Raspberry Pi CNC article in the Sept/Oct 2016 issue of Java Magazine.
 * 
 * @author Stephen Chin
 */
public class CNCStarCreator {

    static GrblController grblController;
    static final CutterListener listener = new CutterListener();
    //static String PORT_NAME = "/dev/ttyACM0";
    static final String PORT_NAME = "/dev/tty.usbmodem1411";
    
    static final double PROBE_OFFSET = 1.045;
    static final int Z_STEPS = 7;
    static final double MATERIAL_THICKNESS = 25.4 * 1/8;
    
    static final List<String> PROBE1 = Arrays.asList("G4P0.005", "M05", "G92.1", "G54", "G10 L2 P1 X0 Y0 Z0", "G21", "G49", "G90", "G10 L2 P1 X0 Y0 Z0", "G0 X-2.5 Z-5", "G0 Z-35.000", "G38.2Z-105 F800", "G4P0.005");
    static final List<String> PROBE2 = Arrays.asList("G0 Z-70", "G38.2Z-182.675F200.0", "G4P0.005");
    static final List<String> PROBE3 = Arrays.asList("G0 Z-5", "G0 X-5");
    static final String COORDINATE_RESET_TEMPLATE = "G10 L20 P0 X220 Y205"; // Z is added based on PRB_Z
    static final List<String> START_SPINDLE = Arrays.asList("G21", "G90", "M3 S9000");
    static final List<String> END_SEQUENCE = Arrays.asList("M5", "$H", "M30");
    
    public static void main(String[] args) throws Exception {
        // Send decimals with "." instead of ",":
        Locale.setDefault(Locale.ENGLISH);
        grblController = new GrblController();
        grblController.addListener(listener);
        grblController.setSingleStepMode(true);
        Boolean openCommPort = grblController.openCommPort(PORT_NAME, 115200);
        if (openCommPort != true) {
            throw new IllegalStateException("Cannot open connection to the cutter");
        }
        waitForConnection();
        homeAndWait();
        sendSequenceAndWait(PROBE1);
        sendSequenceAndWait(PROBE2);
        sendSequenceAndWait(PROBE3);
        System.out.println("PRB_Z = " + listener.prbZ);
        sendCommandAndWait(COORDINATE_RESET_TEMPLATE + " Z" + (PROBE_OFFSET - listener.prbZ));
        
        sendSequenceAndWait(START_SPINDLE);
        moveToStart(50, 100);
        for (int i = 1; i <= Z_STEPS; i++) {
            double newZ = MATERIAL_THICKNESS * (Z_STEPS - i) / Z_STEPS;
            sendCommandAndWait("G1Z" + String.format("%.3f", newZ) + "F355.600");
            sendCommandAndWait("F1117.600");
            sendSequenceAndWait(drawStar(9, 50, 90, 100));
        }
        sendSequenceAndWait(END_SEQUENCE);
        
        TimeUnit.SECONDS.sleep(5);
        grblController.closeCommPort();
    }

    private static void waitForConnection() throws InterruptedException {
        synchronized (listener) {
            while (!listener.connected) {
                listener.wait();
            }
        }
    }
    
    static void homeAndWait() throws Exception {
        synchronized(listener) {
            listener.commandComplete = false;
            grblController.performHomingCycle();
            while (!listener.commandComplete) {
                listener.wait();
            }
        }
    }
    
    static void sendCommandAndWait(String sequence) throws Exception {
        synchronized(listener) {
            grblController.queueCommand(sequence);
            listener.fileStreamComplete = false;
            grblController.beginStreaming();
            while (!listener.fileStreamComplete) {
                listener.wait();
            }
        }
    }
    
    static void sendSequenceAndWait(List<String> sequence) throws Exception {
        synchronized(listener) {
            grblController.queueCommands(sequence);
            listener.fileStreamComplete = false;
            grblController.beginStreaming();
            while (!listener.fileStreamComplete) {
                listener.wait();
            }
        }
    }
    
    static void moveToStart(double innerRadius, double offset) throws Exception {
        sendCommandAndWait("G0X" + String.format("%.3f", innerRadius + offset) + 
            "Y" + String.format("%.3f", offset) + 
            "Z" + String.format("%.3f", MATERIAL_THICKNESS + 1));
    }
    
    static List<String> drawStar(int points, double innerRadius, double outerRadius, double offset) {
        List<String> gcode = new ArrayList<>(points * 2 + 1);
        for (int i=0; i<points * 2; i++) {
            double r = i%2 == 0 ? innerRadius : outerRadius;
            double x = Math.cos(i * Math.PI/points) * r;
            double y = Math.sin(i * Math.PI/points) * r;
            gcode.add("G1X" + String.format("%.3f", x + offset) + 
                "Y" + String.format("%.3f", y + offset));
        }
        gcode.add(gcode.get(0));
        return gcode;
    }
    
    static class CutterListener implements ControllerListener {
        volatile boolean connected;
        volatile boolean commandComplete;
        volatile boolean fileStreamComplete;
        double prbZ;

        @Override
        public synchronized void fileStreamComplete(String string, boolean bln) {
            fileStreamComplete = true;
            notify();
        }

        @Override
        public void commandQueued(GcodeCommand gc) {
        }

        @Override
        public synchronized void commandSent(GcodeCommand gc) {
        }

        @Override
        public synchronized void commandComplete(GcodeCommand gc) {
            commandComplete = true;
            notify();
        }

        @Override
        public void commandComment(String string) {
        }
        
        @Override
        public void messageForConsole(String msg, Boolean verbose) {
            if (!verbose && msg.startsWith("['$H'|'$X' to unlock]")) {
                synchronized (this) {
                    connected = true;
                    notify();
                }
            }
            if (!verbose && msg.startsWith("[PRB:")) {
                String pattern = "\\[PRB\\:-[0-9]*\\.[0-9]*,-[0-9]*\\.[0-9]*,(-[0-9]*\\.[0-9]*)\\:1\\]";
                Matcher matcher = Pattern.compile(pattern).matcher(msg);
                if (matcher.find()) {
                    prbZ = Double.parseDouble(matcher.group(1));
                }
            }
        }

        @Override
        public void statusStringListener(String string, Point3d pntd, Point3d pntd1) {
        }

        @Override
        public void postProcessData(int i) {
        }
        
    }
}
