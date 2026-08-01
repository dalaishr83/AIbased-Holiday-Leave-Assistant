package com.holidayleave.assistant.controller;

import com.holidayleave.assistant.excel.PlannerExcelReader;
import com.holidayleave.assistant.model.LeaveRecord;
import com.holidayleave.assistant.service.AppState;
import com.holidayleave.assistant.service.SyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

/**
 * Main page controller — serves index.html (Thymeleaf template).
 * Also initializes loaded files on first load.
 */
@Controller
public class IndexController {

    @Autowired private AppState appState;
    @Autowired private PlannerExcelReader reader;
    @Autowired private SyncService syncService;

    @GetMapping("/")
    public String index(Model model) {
        // Initialize loaded files on first load if not set
        if (appState.getLoadedFiles().isEmpty()) {
            initLoadedFiles();
        }
        model.addAttribute("appTitle", "Holiday Leave Assistant");
        return "index";
    }

    private void initLoadedFiles() {
        syncService.forceSync();
        List<String> paths = appState.discoverExcelPaths();
        if (!paths.isEmpty()) {
            appState.setLoadedFiles(paths);
            appState.setActiveFiles(Collections.singletonList(paths.get(0))); // newest first
            appState.refreshKnownFiles();
        }
    }
}
