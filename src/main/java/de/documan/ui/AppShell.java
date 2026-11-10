package de.documan.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Meta;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.theme.lumo.Lumo;

/** Rahmen jeder Seite: Lumo als Grundlage, darüber Aussehen und Layout von DocuMan. */
@Push
@StyleSheet(Lumo.STYLESHEET)
@StyleSheet("css/app.css")
@StyleSheet("css/looks.css")
@StyleSheet("css/vaadin.css")
@Meta(name = "color-scheme", content = "light dark")
public class AppShell implements AppShellConfigurator {

    @Override
    public void configurePage(AppShellSettings settings) {
        settings.addFavIcon("icon", "favicon.svg", "any");
        settings.setPageTitle("DocuMan");
    }
}
