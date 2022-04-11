package app;

import app.processors.ConfigProcessor;

import java.util.Arrays;

public class Main {
    public static void main(String[] args) {
        System.out.println(Arrays.toString(ConfigProcessor.splitOnNChar(3, '_', "siema_elo_ko_z_ak")));
    }
}
