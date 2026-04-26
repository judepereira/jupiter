package com.judepereira.jupiter;

import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.server.PWA;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@Push
@PWA(name = "Jupiter",
        shortName = "Jupiter",
        iconPath = "images/logo.png",
        offlinePath = "offline.html",
        offlineResources = {"images/logo.png"})
@CssImport("./styles.css")
public class Application implements AppShellConfigurator {

    static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
