# Detailed description — Spanish

> Paste everything below the line into the Web Store's "Detailed description" field for the Spanish
> listing. The name and the short description come from `_locales/es/messages.json` in the uploaded ZIP,
> not from here.
>
> **The field is plain text.** It renders no Markdown and no HTML: `**bold**` would appear with its
> asterisks, and so the text below has none. What it does keep is line breaks and blank lines, which is
> what separates the headings from the paragraphs under them. Paste it as it is — do not re-add emphasis.

---

Tu página de nueva pestaña, con tus pestañas encima.

stramus convierte la página que abres veinte veces al día en el lugar donde quedan guardadas las páginas
que de verdad usas. Guarda las pestañas que tienes abiertas en una colección, ponle un nombre y ciérralas
sin perderlas. Todo se guarda en tu propio equipo, y funciona así sin necesidad de ninguna cuenta —
inicia sesión solo si quieres tener las mismas colecciones en tu otro navegador.

Guarda tus pestañas abiertas.
El panel de pestañas lista todas las pestañas de todas las ventanas. Arrastra una a una colección, o
guarda toda una ventana de una vez. Una pestaña guardada se puede cerrar en el acto — para eso sirve.

Guarda una página sin abrir stramus.
Un atajo de teclado, un clic derecho o el botón de la barra de herramientas guarda la página en la que
estás — o cualquier enlace de ella — desde la pestaña en la que te encuentres. Una notificación dice
"guardado" al momento, y se convierte en tarjeta la próxima vez que abras stramus.

Organízalas a tu manera.
Las secciones de la barra lateral contienen colecciones; las colecciones contienen tarjetas; las
tarjetas se pueden agrupar bajo tus propios encabezados. Todo funciona arrastrando y soltando, y lo que
se deshace por accidente se puede deshacer.

Las tarjetas no son solo enlaces.
Una tarjeta puede ser un enlace, una nota escrita en Markdown, o un archivo — una imagen, un PDF, lo que
sea que sueltes sobre ella — guardado en la colección junto con los enlaces a los que pertenece.

Encuéntralo con una sola tecla.
El buscador busca a la vez en tus tarjetas, tus pestañas abiertas y tu historial de navegación, y ordena
los resultados según lo que realmente abres. Un buscador vacío ya muestra tus sitios más visitados. Si lo
que escribiste es una búsqueda y no una página, va a tu propio buscador predeterminado.

Pregúntale al modelo del propio navegador.
Donde Chrome tiene un modelo en el dispositivo, el buscador te ofrecerá preguntarle — en una ventana
sobre tu colección, en tu propio equipo, sin enviar nada a ningún sitio. ¿Prefieres un asistente web?
Elige ChatGPT, Gemini o Claude en los ajustes, y tu pregunta se abrirá en un chat con ellos.

Guárdate una parte para ti.
Una sección se puede bloquear con un PIN, para que lo que hay detrás no aparezca en pantalla cuando la
compartes.

A tu gusto.
Cinco temas, cada uno con su mitad clara y su mitad oscura, un color de acento tuyo y un fondo detrás de
la aplicación — un degradado o una imagen propia. Una colección puede llevar una marca: un símbolo con
color, o un emoji. Dos densidades de tarjeta y tres radios de esquina, por si eso te importa.

Y el resto.
Once idiomas de interfaz, del inglés al turco. Importar desde tus marcadores, un archivo CSV, una
exportación de OneTab o una de Toby; exportar de vuelta a marcadores o CSV, o llevarte toda la base de
datos en un único archivo de copia de seguridad. Fotogramas de los vídeos guardados, apagados hasta que
los enciendas. Ordenar pestañas. Una caché de favicons, para que tus enlaces conserven sus iconos sin
conexión.

Privado por construcción.
Sin analítica, sin telemetría, sin rastreo, sin publicidad — con o sin cuenta. Sin cuenta, ninguno de tus
datos sale de tu equipo: tus colecciones no tienen ningún servidor al que ir. Con una cuenta se
sincronizan para que tu otro navegador pueda tenerlas — y nada más se sincroniza: tus estadísticas de
navegación se quedan en este equipo a menos que las actives tú mismo, y tu historial de navegación nunca
se sube. stramus lee tus pestañas y tu historial porque eso es lo que hace un gestor de pestañas, y los
lee en el momento, sin guardar ninguna copia.

Los iconos de los sitios vienen del propio almacén de iconos de tu navegador, así que un sitio que ya
visitaste se dibuja sin preguntar a nadie. Para un sitio que no has visitado, el icono lo obtiene nuestro
servidor en tu nombre — de forma anónima, sin ninguna cuenta asociada y sin registrar nada — en lugar de
entregar tus direcciones guardadas, una a una, a un servicio público de iconos.

Un PIN en una sección la quita de la pantalla; no es cifrado, y no la oculta del servidor. La política de
privacidad lo dice con claridad, y también qué más significa tener una cuenta.

Novedades de la 1.5.2.
La búsqueda perdona una errata y perdona una distribución de teclado olvidada: una consulta escrita en la
que no era se vuelve a preguntar con la otra, así que «ыекфьгы» encuentra Stramus. Ambas cosas valen para
tus enlaces, tus pestañas abiertas y el historial; el historial lo busca el propio navegador, letra por
letra, así que un fallo allí recurre a las páginas que has visto hace poco. Los ajustes tienen su propia
búsqueda: atenúa lo que no coincide y se desplaza hasta lo que sí, en vez de esconder el resto. La sección
de guardado rápido ya no se crea por segunda vez en un navegador que ya la tenía. La sincronización sube
una cuenta grande por páginas igual que la baja, y un grupo borrado en otra máquina ya no deja sus enlaces
colgando de él.
También sube de versión la capa de índices bajo la base de datos: una consulta sobre un índice compuesto
podía devolver una fila de más, y así un duplicado sobrevivía a una importación o a una fusión.

Política de privacidad: https://stramus.space/privacy.html
Código fuente: https://github.com/olluorg/stramus
