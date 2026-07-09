package com.yayatechandinnovations.yayaidentity.api;

import com.yayatechandinnovations.yayaidentity.roles.PermissionCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** App-permission manifest (design §9.4, US-30): an app declares its permission catalog. */
@RestController
@RequestMapping("/v1/identity/apps/{app}/permissions")
public class AppPermissionController {

    private final PermissionCatalogService catalog;

    public AppPermissionController(PermissionCatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping
    public List<String> declare(@PathVariable String app, @RequestBody List<String> keys) {
        catalog.declare(app, keys);
        return catalog.declaredFor(app);
    }

    @GetMapping
    public List<String> list(@PathVariable String app) {
        return catalog.declaredFor(app);
    }
}
