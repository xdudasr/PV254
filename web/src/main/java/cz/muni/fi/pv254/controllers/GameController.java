package cz.muni.fi.pv254.controllers;

import cz.muni.fi.pv254.algorithms.CollaborativeFiltering;
import cz.muni.fi.pv254.dto.*;
import cz.muni.fi.pv254.enums.AlgorithmType;
import cz.muni.fi.pv254.exceptions.ResourceNotFoundException;
import cz.muni.fi.pv254.facade.AlgorithmRatingFacade;
import cz.muni.fi.pv254.facade.GameFacade;
import cz.muni.fi.pv254.facade.RecommendationFacade;
import cz.muni.fi.pv254.facade.UserFacade;
import cz.muni.fi.pv254.parsing.App;
import cz.muni.fi.pv254.algorithms.contentBasedAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

@Controller
@RequestMapping("/game")
public class GameController {

    final static Logger log = LoggerFactory.getLogger(GameController.class);

    @Autowired
    private App app;

    @Autowired
    private GameFacade gameFacade;

    @Autowired
    private RecommendationFacade recommendationFacade;

    @Autowired
    private UserFacade userFacade;

    @Autowired
    private AlgorithmRatingFacade algorithmRatingFacade;

    @Autowired
    private contentBasedAlgorithm contentBased;

    @Autowired
    private CollaborativeFiltering collaborative;

    @RequestMapping(value = "/download", method = RequestMethod.GET)
    public String Index(Model model,
                       HttpServletRequest req,
                       HttpServletResponse res){

        return "/game/download";
    }

    @RequestMapping(value = "/download", method = RequestMethod.POST)
    public String Download(@RequestParam String steamId,
                           @RequestParam int minReviews,
                           RedirectAttributes redirectAttributes){

        app.setMinReviews(minReviews);
        app.setDebug(2);
        app.setOffsetDiff(100);
        app.inteligentParse(Long.parseLong(steamId));

        return "/game/download";
    }



    @RequestMapping(value = "/rate/{step}", method = RequestMethod.GET)
    public String RateGames(
            @PathVariable("step") int step,
            Model model,
            HttpServletRequest req,
            RedirectAttributes redirectAttributes){

        UserDTO authUser = (UserDTO) req.getSession().getAttribute("authUser");

        if (authUser != null){

            List<RecommendationDTO> recommendations = recommendationFacade.findByAuthor(authUser);;

            Long stepCount = Math.min(gameFacade.countGames() / 10, 10);
            if (recommendations.size() >= stepCount){
                redirectAttributes.addFlashAttribute("alert_danger", "Games already rated!");
                return "redirect:/";
            }

            if (recommendations.size() > 0 && recommendations.size() != step)
                return "redirect:/game/rate/" + recommendations.size();

            List<GameDTO> games = gameFacade.findAll();

            if (games.size() < 10 * (step + 1)){
                redirectAttributes.addFlashAttribute("alert_danger", "Not enough games");
                return "redirect:/";
            }


            games.sort(Comparator.comparing(GameDTO::getSteamId));
            games = games.subList(step * 10, (step * 10) + 9);
            model.addAttribute("games",games);
            model.addAttribute("step", step);
            model.addAttribute("steps", stepCount);
            populateGenres(games, model);

            return "game/rate";
        }
        return loginRedirect(redirectAttributes);
    }

    @RequestMapping(value = "/rate/{step}/{id}/{like}", method = RequestMethod.POST)
    public String RateSingle(
            @PathVariable("step") int step,
            @PathVariable("id") Long id,
            @PathVariable ("like") int like,
            Model model,
            HttpServletRequest req,
            RedirectAttributes redirectAttributes){

        UserDTO authUser = (UserDTO) req.getSession().getAttribute("authUser");

        if (authUser != null){
            RecommendationDTO recommend = new RecommendationDTO();
            UserDTO user = userFacade.findById(authUser.getId());
            recommend.setAuthor(user);
            GameDTO game = gameFacade.findById(id);
            if (game == null)
                throw new ResourceNotFoundException();
            recommend.setGame(game);
            recommend.setVotedUp(like == 1);
            recommendationFacade.add(recommend);

            Long stepCount = Math.min(gameFacade.countGames() / 10, 10);

            if (step == (stepCount - 1)){
                redirectAttributes.addFlashAttribute("alert_info", "Games Successfully Rated!");
                return "redirect:/";
            }

            return "redirect:/game/rate/" + (step + 1);
        }
        return loginRedirect(redirectAttributes);
    }

    @RequestMapping(value = "/recommend", method = RequestMethod.GET)
    public String Recommend(HttpServletRequest req,
                            RedirectAttributes redirectAttributes){
        UserDTO authUser = (UserDTO) req.getSession().getAttribute("authUser");
        if (authUser == null){
            return loginRedirect(redirectAttributes);
        }

        Long stepCount = Math.min(gameFacade.countGames() / 10, 10);
        List<RecommendationDTO> recommendations = recommendationFacade.findByAuthor(authUser);
        if (recommendations.size() < stepCount){
            redirectAttributes.addFlashAttribute("alert_info", "Please finish rating games");
            return "redirect:/game/rate/" + recommendations.size();
        }

        int random = (int)(Math.random() * 7);
        switch (random){
            case 0 : return "redirect:/game/collaborativePearson";
            case 1 : return "redirect:/game/collaborativeDice";
            case 2 : return "redirect:/game/collaborativePearsonSubset";
            case 3 : return "redirect:/game/collaborativeDiceSubset";
            case 4 : return "redirect:/game/descriptionBased";
            case 5 : return "redirect:/game/genreBasedFrequent";
            case 6 : return "redirect:/game/genreBased";
        }

        return "game/recommend";
    }

    @RequestMapping("/collaborativePearson")
    public String CollaborativePearson(Model model,
                                       HttpServletRequest req,
                                       RedirectAttributes redirectAttributes){

        UserDTO authUser = (UserDTO) req.getSession().getAttribute("authUser");
        String redirect = canGetRecommendation(req, redirectAttributes, recommendationFacade.findByAuthor(authUser));
        if (redirect != null)
            return redirect;

        List<GameDTO> games = collaborative.nearestNeighborIntersection(authUser.getId(), true);
        if (games.size() > 5)
            games = games.subList(0, 4);
        model.addAttribute("games",games);

        if (games.size() < 10)
            populatePictures(games, model);
        populateGenres(games, model);

        setCanRateAlgorithm(model, authUser, AlgorithmType.CollaborativePearson);

        return "game/games";
    }

    @RequestMapping("/collaborativeDice")
    public String CollaborativeDice(Model model,
                                       HttpServletRequest req,
                                       RedirectAttributes redirectAttributes){
        UserDTO authUser = (UserDTO) req.getSession().getAttribute("authUser");
        String redirect = canGetRecommendation(req, redirectAttributes, recommendationFacade.findByAuthor(authUser));
        if (redirect != null)
            return redirect;

        List<GameDTO> games = collaborative.nearestNeighborIntersection(authUser.getId(), false);
        if (games.size() > 5)
            games = games.subList(0, 4);
        model.addAttribute("games",games);

        if (games.size() < 10)
            populatePictures(games, model);
        populateGenres(games, model);

        setCanRateAlgorithm(model, authUser, AlgorithmType.CollaborativeDice);

        return "game/games";
    }

    @RequestMapping("/collaborativePearsonSubset")
    public String CollaborativePearsonSubset(Model model,
                                       HttpServletRequest req,
                                       RedirectAttributes redirectAttributes){
        UserDTO authUser = (UserDTO) req.getSession().getAttribute("authUser");
        String redirect = canGetRecommendation(req, redirectAttributes, recommendationFacade.findByAuthor(authUser));
        if (redirect != null)
            return redirect;

        List<GameDTO> games = collaborative.nearestNeighborSubset(authUser.getId(), true);
        if (games.size() > 5)
            games = games.subList(0, 4);
        model.addAttribute("games",games);

        if (games.size() < 10)
            populatePictures(games, model);
        populateGenres(games, model);

        setCanRateAlgorithm(model, authUser, AlgorithmType.CollaborativePearsonSubset);

        return "game/games";
    }

    @RequestMapping("/collaborativeDiceSubset")
    public String CollaborativeDiceSubset(Model model,
                                    HttpServletRequest req,
                                    RedirectAttributes redirectAttributes){
        UserDTO authUser = (UserDTO) req.getSession().getAttribute("authUser");
        String redirect = canGetRecommendation(req, redirectAttributes, recommendationFacade.findByAuthor(authUser));
        if (redirect != null)
            return redirect;

        List<GameDTO> games = collaborative.nearestNeighborSubset(authUser.getId(), false);
        if (games.size() > 5)
            games = games.subList(0, 4);
        model.addAttribute("games",games);

        if (games.size() < 10)
            populatePictures(games, model);
        populateGenres(games, model);

        setCanRateAlgorithm(model, authUser, AlgorithmType.CollaborativeDiceSubset);

        return "game/games";
    }

    @RequestMapping("/descriptionBased")
    public String DescriptionBased(Model model,
                                       HttpServletRequest req,
                                       RedirectAttributes redirectAttributes){

        UserDTO authUser = (UserDTO) req.getSession().getAttribute("authUser");
        String redirect = canGetRecommendation(req, redirectAttributes, recommendationFacade.findByAuthor(authUser));
        if (redirect != null)
            return redirect;

        Set<GameDTO> games = contentBased.recommendationByWord(authUser);
        model.addAttribute("games",games);

        if (games.size() < 10)
            populatePictures(games, model);
        populateGenres(games, model);

        setCanRateAlgorithm(model, authUser, AlgorithmType.DescriptionBased);

        return "game/games";
    }

    @RequestMapping("/genreBasedFrequent")
    public String GenreBasedFrequent(Model model,
                                       HttpServletRequest req,
                                       RedirectAttributes redirectAttributes){
        UserDTO authUser = (UserDTO) req.getSession().getAttribute("authUser");
        String redirect = canGetRecommendation(req, redirectAttributes, recommendationFacade.findByAuthor(authUser));
        if (redirect != null)
            return redirect;

        Set<GameDTO> games = contentBased.recommendationFrequentByTag(authUser);
        model.addAttribute("games",games);
        if (games.size() < 10)
            populatePictures(games, model);
        populateGenres(games, model);

        setCanRateAlgorithm(model, authUser, AlgorithmType.GenreBasedFrequent);

        return "game/games";
    }

    @RequestMapping("/genreBased")
    public String GenreBased(Model model,
                             HttpServletRequest req,
                             RedirectAttributes redirectAttributes){
        UserDTO authUser = (UserDTO) req.getSession().getAttribute("authUser");
        String redirect = canGetRecommendation(req, redirectAttributes, recommendationFacade.findByAuthor(authUser));
        if (redirect != null)
            return redirect;

        Set<GameDTO> games = contentBased.recommendationByTag(authUser);
        model.addAttribute("games",games);
        if (games.size() < 10)
            populatePictures(games, model);
        populateGenres(games, model);

        setCanRateAlgorithm(model, authUser, AlgorithmType.GenreBased);


        return "game/games";
    }

    @RequestMapping("/rated")
    public String MyGames(Model model,
                          HttpServletRequest req,
                          RedirectAttributes redirectAttributes){


        UserDTO authUser = (UserDTO) req.getSession().getAttribute("authUser");
        String redirect = canGetRecommendation(req, redirectAttributes, recommendationFacade.findByAuthor(authUser));
        if (redirect != null)
            return redirect;
        List<GameDTO> games = gameFacade.findRecommendedByUser(authUser);
        model.addAttribute("games",games);
        if (games.size() < 10)
            populatePictures(games, model);
        populateGenres(games, model);

        model.addAttribute("canRateAlgorithm", false);

        return "game/games";
    }

    @RequestMapping(value = "/rateAlgorithm/{algoType}/{like}", method = RequestMethod.POST)
    public String RateAlgorithm(Model model,
                                HttpServletRequest req,
                                RedirectAttributes redirectAttributes,
                                @PathVariable AlgorithmType algoType,
                                @PathVariable boolean like){
        UserDTO authUser = (UserDTO) req.getSession().getAttribute("authUser");

        AlgorithmRatingDTO rating = algorithmRatingFacade.findByAuthorAndType(authUser, algoType);
        if (rating == null){
            AlgorithmRatingDTO ratingDTO = new AlgorithmRatingDTO(authUser, like, algoType);
            algorithmRatingFacade.add(ratingDTO);
        }

        return "redirect:/";
    }

    private String loginRedirect(RedirectAttributes redirectAttributes) {

        redirectAttributes.addFlashAttribute("alert_info", "Please log in.");
        return "redirect:/auth/login";
    }

    private String canGetRecommendation(HttpServletRequest req,
                                        RedirectAttributes redirectAttributes,
                                        List<RecommendationDTO> recommendations){
        UserDTO authUser = (UserDTO) req.getSession().getAttribute("authUser");
        if (authUser == null){
            return loginRedirect(redirectAttributes);
        }
        Long stepCount = Math.min(gameFacade.countGames() / 10, 10);
        if (recommendations.size() < stepCount){
            redirectAttributes.addFlashAttribute("alert_info", "Please finish rating games");
            return "redirect:/game/rate/" + recommendations.size();
        }

        return null;
    }

    private void populatePictures(Collection<GameDTO> games, Model model){
        Map<Long, String> pictures = new HashMap<>();

        for (GameDTO game: games) {
            pictures.put(game.getId(), app.downloadGamePictureUrl(game.getSteamId()));
        }

        model.addAttribute("pictures", pictures);
    }

    private void populateGenres(Collection<GameDTO> games, Model model){
        Map<Long, String> genres = new HashMap<>();

        for (GameDTO game: games) {
            String genresMerged = "";
            for (GenreDTO genre: game.getGenres()) {
                genresMerged += genre.getName() + ", ";
            }
            if (genresMerged.length() > 2)
                genresMerged = genresMerged.substring(0, genresMerged.length() - 2);
            genres.put(game.getId(), genresMerged);
        }

        model.addAttribute("genres", genres);
    }

    private void setCanRateAlgorithm(Model model, UserDTO user, AlgorithmType algoType){
        model.addAttribute("algorithm", algoType);
        AlgorithmRatingDTO rating = algorithmRatingFacade.findByAuthorAndType(user, algoType);
        model.addAttribute("canRateAlgorithm", rating == null);
    }
}
