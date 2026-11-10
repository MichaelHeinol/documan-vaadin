package de.documan.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/** Rahmen der Seiten ohne Anmeldung: Anmelden, Registrieren, Passwort. Ein Blatt in der Mitte. */
@AnonymousAllowed
public class AuthLayout extends Div implements RouterLayout {

    public AuthLayout() {
        setClassName("auth");
    }
}
