# Detailed description — German

> Paste everything below the line into the Web Store's "Detailed description" field for the German
> listing. The name and the short description come from `_locales/de/messages.json` in the uploaded ZIP,
> not from here.
>
> **The field is plain text.** It renders no Markdown and no HTML: `**bold**` would appear with its
> asterisks, and so the text below has none. What it does keep is line breaks and blank lines, which is
> what separates the headings from the paragraphs under them. Paste it as it is — do not re-add emphasis.

---

Deine Seite für neue Tabs, mit deinen Tabs darauf.

stramus macht aus der Seite, die du zwanzigmal am Tag öffnest, den Ort, an dem die Seiten liegen, die du
wirklich brauchst. Speichere deine offenen Tabs in einer Sammlung, gib ihr einen Namen und schließe sie,
ohne sie zu verlieren. Alles wird auf deinem eigenen Rechner gespeichert, und das funktioniert ganz ohne
Konto — melde dich nur an, wenn du dieselben Sammlungen auch in deinem anderen Browser haben willst.

Speichere deine offenen Tabs.
Die Tab-Leiste zeigt jeden Tab in jedem Fenster. Ziehe einen in eine Sammlung, oder speichere gleich ein
ganzes Fenster. Ein gespeicherter Tab kann sofort geschlossen werden — genau darum geht es.

Speichere eine Seite, ohne stramus zu öffnen.
Ein Tastenkürzel, ein Rechtsklick oder die Schaltfläche in der Symbolleiste speichert die Seite, auf der
du gerade bist — oder jeden Link darauf —, aus welchem Tab auch immer. Eine Benachrichtigung sagt sofort
"gespeichert", und beim nächsten Öffnen von stramus wird daraus eine Karte.

Ordne sie, wie du denkst.
Bereiche in der Seitenleiste enthalten Sammlungen, Sammlungen enthalten Karten, und Karten lassen sich
unter eigenen Überschriften gruppieren. Alles per Drag-and-drop, und alles, was aus Versehen passiert,
lässt sich rückgängig machen.

Karten sind nicht nur Links.
Eine Karte kann ein Link sein, eine in Markdown geschriebene Notiz oder eine Datei — ein Bild, ein PDF,
was auch immer du darauf ziehst — und liegt in der Sammlung neben den Links, zu denen sie gehört.

Finde es mit einem Tastendruck.
Die Suchleiste durchsucht gleichzeitig deine Karten, deine offenen Tabs und deinen Browserverlauf und
zeigt zuerst, was du tatsächlich öffnest. Ein leeres Suchfeld zeigt schon deine meistbesuchten Seiten.
Tippst du eher eine Suchanfrage als eine Adresse ein, geht sie an deine eigene Standardsuchmaschine.

Frag das Modell deines Browsers.
Hat Chrome ein Modell direkt auf dem Gerät, bietet die Suchleiste an, es zu fragen — in einem Fenster
über deiner Sammlung, auf deinem Rechner, ohne dass irgendetwas irgendwohin gesendet wird. Lieber ein
Web-Assistent? Wähle in den Einstellungen ChatGPT, Gemini oder Claude, und deine Frage öffnet sich
stattdessen in einem Chat mit ihnen.

Behalte einen Teil für dich.
Ein Bereich lässt sich mit einer PIN sperren, sodass das, was dahinter liegt, nicht auf dem Bildschirm
erscheint, wenn du ihn teilst.

Mach es zu deinem.
Fünf Designs, jedes mit einer hellen und einer dunklen Hälfte, eine Akzentfarbe deiner Wahl und ein
Hintergrund hinter der App — ein Verlauf oder ein eigenes Bild. Eine Sammlung lässt sich mit einem
farbigen Zeichen oder einem Emoji markieren. Zwei Kartendichten und drei Eckenradien, falls dir so etwas
wichtig ist.

Und der Rest.
Elf Sprachen für die Oberfläche, von Englisch bis Türkisch. Import aus deinen Lesezeichen, einer
CSV-Datei, einem OneTab- oder einem Toby-Export; Export zurück in Lesezeichen oder CSV, oder die ganze
Datenbank als eine einzige Sicherungsdatei. Standbilder für gespeicherte Videos, aus, bis du sie
einschaltest. Tabs sortieren. Ein Favicon-Cache, damit deine Links ihre Symbole auch offline behalten.

Privat, von Grund auf.
Keine Analyse, keine Telemetrie, kein Tracking, keine Werbung — mit oder ohne Konto. Ohne Konto verlässt
kein einziges deiner Daten den Rechner: deine Sammlungen haben keinen Server, zu dem sie gehen könnten.
Mit Konto werden sie synchronisiert, damit dein anderer Browser sie auch hat — und sonst nichts:
deine Nutzungsstatistik bleibt auf diesem Rechner, solange du sie nicht selbst einschaltest, und dein
Browserverlauf wird nie hochgeladen. stramus liest deine Tabs und deinen Verlauf, weil genau das die
Aufgabe eines Tab-Managers ist — und liest sie im Moment der Nutzung, ohne eine Kopie zu behalten.

Seiten-Icons kommen aus dem eigenen Icon-Speicher deines Browsers, sodass eine besuchte Seite gezeichnet
wird, ohne jemanden zu fragen. Für eine Seite, die du noch nicht besucht hast, holt unser Server das Icon
für dich — anonym, ohne Konto und ohne Protokoll —, statt deine gespeicherten Adressen einem öffentlichen
Icon-Dienst einzeln mitzuteilen.

Eine PIN auf einem Bereich hält ihn vom Bildschirm fern; sie ist keine Verschlüsselung und verbirgt den
Bereich nicht vor dem Server. Das steht so klar in der Datenschutzerklärung, ebenso wie das, was ein
Konto sonst noch bedeutet.

Neu in 1.5.2.
Die Suche verzeiht jetzt einen Tippfehler — und eine vergessene Tastaturbelegung: Eine in der falschen
getippte Anfrage wird noch einmal in der anderen gestellt, sodass „ыекфьгы“ Stramus findet. Beides gilt
für Ihre Links, Ihre offenen Tabs und den Verlauf; den Verlauf durchsucht der Browser selbst, Buchstabe
für Buchstabe, deshalb greift ein Fehlschlag dort auf die zuletzt gesehenen Seiten zurück. Die
Einstellungen haben eine eigene Suche: Sie dämpft, was nicht passt, und scrollt zum Treffer, statt alles
Übrige zu verbergen. Der Bereich für Schnellspeicherungen entsteht in einem Browser, der ihn schon hat,
kein zweites Mal. Die Synchronisierung schickt ein großes Konto ebenso seitenweise hinauf, wie sie es
herunterholt, und eine auf einem anderen Gerät gelöschte Gruppe lässt ihre Links nicht mehr an sich hängen.
Auch die Indexschicht unter der Datenbank ist eine Version neuer: Eine Abfrage über einen
zusammengesetzten Index konnte eine Zeile zu viel zurückgeben — so überlebte ein Duplikat einen Import
oder eine Zusammenführung.

Datenschutzerklärung: https://stramus.space/privacy.html
Quellcode: https://github.com/olluorg/stramus
