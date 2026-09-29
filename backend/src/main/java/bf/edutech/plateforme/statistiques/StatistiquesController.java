package bf.edutech.plateforme.statistiques;

import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.statistiques.StatistiquesService.RapportVue;

/** Statistiques d'une année : consultation et classeur Excel. */
@RestController
public class StatistiquesController {

    static final String LECTURE = "hasAnyRole('ADMIN_ECOLE','CENSEUR','SECRETARIAT','INTENDANT')";
    private static final MediaType EXCEL =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final StatistiquesService statistiques;
    private final RapportExcel excel;

    StatistiquesController(StatistiquesService statistiques, RapportExcel excel) {
        this.statistiques = statistiques;
        this.excel = excel;
    }

    @GetMapping("/api/v1/annees/{anneeId}/statistiques")
    @PreAuthorize(LECTURE)
    public RapportVue rapport(@PathVariable UUID anneeId) {
        return statistiques.rapport(anneeId);
    }

    @GetMapping("/api/v1/annees/{anneeId}/statistiques/excel")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> classeur(@PathVariable UUID anneeId) {
        RapportVue r = statistiques.rapport(anneeId);
        return ResponseEntity.ok().contentType(EXCEL)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("statistiques-" + r.annee().libelle() + ".xlsx").build().toString())
                .body(excel.produire(r));
    }
}
