package com.paxaris.product_management_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
public class ProjectManagerService {

	public static void main(String[] args) {
		SpringApplication.run(ProjectManagerService.class, args);

	}

}





//have to do 3 open api spec (create new project )load on frontend when load all of the uris created automatically
//		at the time of signup store the uri in the db