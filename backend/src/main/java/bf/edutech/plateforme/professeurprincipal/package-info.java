/**
 * Professeur principal de la classe (v0.49.1). Un enseignant de la classe, désigné par le chef d'établissement ou le
 * censeur, sert d'interface :
 * <ul>
 * <li>entre les enseignants de la classe et l'administration : il recueille leurs revendications et les porte à la
 * direction (dossiers « revendication ») ;</li>
 * <li>entre les élèves et les enseignants : un élève ou un groupe d'élèves qui a un souci avec un enseignant se tourne
 * vers lui ; il suit la médiation, informe l'enseignant, saisit la direction si besoin (dossiers « médiation ») ;</li>
 * <li>pour les notes : avant le conseil de classe, il vérifie avec les élèves les notes et moyennes de la période,
 * recueille les réclamations, que l'enseignant de la matière corrige ou maintient, puis atteste la vérification. Les
 * bulletins et l'avis du conseil d'une classe qui a un professeur principal attendent cette attestation.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(displayName = "Professeur principal")
package bf.edutech.plateforme.professeurprincipal;
