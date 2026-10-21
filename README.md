# Golf Swing Analyzer (Android, Kotlin)

Application pour filmer un swing de golf **de derrière le golfeur** (téléphone sur trépied),
en slow-motion, puis revoir le swing et estimer la trajectoire de la balle.

## Flux utilisateur

1. **Prévisualisation 60 fps** pour cadrer (téléphone sur trépied, derrière le golfeur).
2. **Signe de la main** devant la caméra (ou bouton « Démarrer ») : bip, puis enregistrement.
3. **Enregistrement en slow-motion** en arrière-plan, avec un compte à rebours de **18 s**.
4. **Revue** : lecture en slow-motion ou en vitesse réelle, toucher pour repérer la balle sur la
   première image, puis « Suivre la balle » : trajectoire tracée sur la vidéo, vitesses et carry estimés.
5. **Enregistrer** la vidéo (Movies/GolfSwing) et les données JSON (Download/GolfSwing),
   ou **Nouveau swing** pour reboucler.

## Import et compilation

Prérequis : Android Studio Ladybug (2024.2) ou plus récent, JDK 17, Android 10 (API 29) minimum.

1. Dézippez l'archive.
2. Android Studio : **File > Open** puis sélectionnez le dossier `GolfSwingAnalyzer`.
3. Si Android Studio signale un `gradle-wrapper.jar` manquant, ouvrez le terminal intégré et lancez :
   ```
   gradle wrapper --gradle-version 8.9
   ```
   (ou installez Gradle 8.9 et relancez la commande). Puis **Sync Project with Gradle Files**.
4. Branchez le Pixel 7 Pro en débogage USB, puis **Run ▶**.

Le téléphone doit être en **portrait**, caméra arrière utilisée.

## Capacités détectées

`CameraDetector` (CameraController.kt) lit les capacités réelles de la caméra arrière :
- sessions **haute vitesse** (`ConstrainedHighSpeedCaptureSession`) : 240 fps si disponible, sinon 120 fps ;
- repli automatique à **60 fps standard** (ralenti logiciel) si aucune haute vitesse n'est exposée ;
- focale et taille du capteur, pour estimer la profondeur de la balle.

## Détection du signe de la main

`WaveDetector.kt` : différence entre images (plan Y, sous-échantillonné), suivi du centre du mouvement.
Un déclenchement a lieu après **3 inversions de sens en 1,8 s**. Les mouvements globaux (secousses,
changements de lumière) sont ignorés. Pour ajuster : seuil de différence `d > 28`, nombre d'inversions
et fenêtre dans `WaveDetector.process`.

## Estimation de la trajectoire (SwingAnalyzer.kt)

- Décodage de la vidéo image par image (MediaCodec -> ImageReader) ;
- suivi de la balle : blob le plus lumineux dans une fenêtre de recherche autour de la position prédite ;
- profondeur : `Z = f · D / d` (D = 42,67 mm, d = diamètre apparent en pixels) ;
- ajustement quadratique sur les 0,35 s après l'impact -> vitesse initiale 3D ;
- **carry** : intégration numérique avec traînée (sans effet Magnus/spin) ;
- **vitesse de club** : vitesse balle / 1,50 (smash factor driver, à régler dans `SMASH_FACTOR`).

### Limites à connaître

- Ce sont des **estimations indicatives** (ordre de grandeur ±15-20 %), pas une mesure de type Trackman.
- Le spin (backspin, side spin) n'est pas mesuré : le carry est sous-estimé pour un driver.
- La caméra doit être **stable et à peu près horizontale**. Une balle très loin (> 30-40 m) devient trop petite pour être suivie.
- Le suivi est sensible aux objets très brillants (vêtements blancs, ciel) : si le suivi déraille, touchez à nouveau la balle sur la première image puis relancez l'analyse.
- Le repérage doit se faire **à l'image de la pause** : la première image est le plus simple.
- Le projet n'a pas été compilé ni testé sur appareil par son auteur : prévoyez de corriger de petites erreurs
  de compilation éventuelles et de régler les seuils (détection du geste, smash factor) sur votre téléphone.

## Paramètres utiles

| Où | Quoi |
|---|---|
| `MainActivity.RECORD_MS` | durée d'enregistrement (18 s) |
| `WaveDetector` | sensibilité du geste |
| `SwingAnalyzer.SMASH_FACTOR` | estimation de la vitesse de club |
| `SwingAnalyzer.DRAG_K` | traînée pour le carry |
| `ReviewActivity.SPEEDS` | vitesses de lecture (0,1× à 1×) |

## Version 2 : déclenchement, décompte et flash

- **Gestes guidés** : la carte du haut indique les étapes ; une main animée (👋) montre le geste à faire ;
  3 points se remplissent à chaque aller-retour détecté.
- **Anti-faux déclenchements** : chaque aller simple doit dépasser une amplitude minimale, 3 allers valides
  sont exigés dans 2,2 s, le mouvement doit rester dans une plage de surface (ni secousse, ni déplacement
  global), la détection est armée 2 s après l'ouverture de l'écran et un délai de 4 s suit chaque déclenchement.
  Le bouton « Sensibilité » règle les seuils (Faible / Normale / Élevée).
- **Décompte annulable** : après un geste, un bouton ou une touche, le décompte 3-2-1 démarre (bips et
  flash court à chaque seconde). Toucher l'écran ou le bouton « Annuler » arrête le processus.
- **Déclenchement manuel** : bouton volume haut/bas, déclencheur d'appareil photo, bouton média ou
  « Entrée » d'une télécommande Bluetooth (`dispatchKeyEvent`). Pendant l'enregistrement, la même touche
  arrête l'enregistrement plus tôt.
- **Flash clignotant** pendant l'enregistrement (torche, environ 1 cycle par seconde) pour que le golfeur
  sache que la caméra tourne. Si le téléphone n'a pas de lampe, l'écran clignote en blanc à la place.
  Point d'attention : le flash modifie légèrement l'exposition de la vidéo et, sur certains appareils, la
  lampe peut ne pas être pilotable en session haute vitesse (le clignotement est alors ignoré).
