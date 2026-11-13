# Diseño aprobado del celular

El médico lo aprobó el 2026-11-03 («déjalo así»). Son los archivos del lienzo: HTML con **datos de ejemplo**, nunca datos reales. De aquí salen los colores, las medidas y los textos de `app/.../ui/phone`.

**Tema oscuro (K10).** El diseño es claro y así se queda. Con el sistema en modo oscuro, el celular usa los mismos tokens con los tonos oscuros de la PC
(`PhonePalette.Dark` en `PhoneDesign.kt`): el acento como texto se aclara para leerse y los botones principales conservan su azul con texto blanco. Cada par de texto y
fondo cumple 4.5:1 en los dos temas (`PhonePaletteTest`). Las barras de ventas del diseño (celeste claro sobre blanco) se dejan como están en el claro.
