package com.lups.cluster_starter.controller;

import com.lups.cluster_starter.service.AppStarterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/files")
public class AppController {
    @Autowired
    private final AppStarterService appStarterService;

    @Autowired
    public AppController(AppStarterService appStarterService) {

        this.appStarterService = appStarterService;
    }

    @PostMapping("/publish")
    public String publishFiles(@RequestParam("file") List<MultipartFile> files) {

        return appStarterService.publishCodeFilesToClusterExecutor(files);
    }

    /*@PostMapping("/uploadResultFiles/{resultDirId}")
    public void handleFileUpload(@RequestBody String result) {

        new ResponseEntity<>(this.appStarterService.storeCodeFiles(result), HttpStatus.OK);
    }*/

    @GetMapping("/downloadResults/{resultDirId}")
    public ResponseEntity<Resource> getResult(@PathVariable String resultDirId) {

        return this.appStarterService.retrieveResult(resultDirId);
    }
}
