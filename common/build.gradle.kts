// :common -- what every NYR fix shares. It is shaded into each fix's jar and relocated under that fix's own package, so two
// NYR fixes on one server never hand each other their classes.
plugins {
    `java-library`
}

dependencies {
    api(libs.folialib)
}
