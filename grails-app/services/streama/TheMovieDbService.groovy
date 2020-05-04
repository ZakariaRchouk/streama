package streama

import groovy.json.JsonSlurper
import grails.transaction.Transactional
import org.hibernate.boot.archive.internal.UrlInputStreamAccess

import java.util.concurrent.ConcurrentHashMap

@Transactional
class TheMovieDbService {

  def BASE_URL = "https://api.themoviedb.org/3"

  def apiCacheData = new ConcurrentHashMap()

  def getAPI_PARAMS(){
    return "api_key=$API_KEY&language=$API_LANGUAGE"
  }
  def getAPI_PARAMS_WITHOUT_LANG(){
    return "api_key=$API_KEY"
  }

  def getAPI_KEY(){
    return Settings.findBySettingsKey('TheMovieDB API key')?.value
  }

  def getAPI_LANGUAGE(){
    return Settings.findBySettingsKey('TheMovieDB API language')?.value
  }

  def validateApiKey(apiKey){
    def JsonContent = new URL(BASE_URL + '/configuration?api_key=' + apiKey).getText("UTF-8")
    return new JsonSlurper().parseText(JsonContent)
  }

  def validateLanguage(language){
    def locale = Locale.forLanguageTag(language)
    return locale.toLanguageTag().equals(language)
  }

  def getSimilarMovies(movieId){
    def jsonContentSimilarMovies = new URL(BASE_URL + "/movie/$movieId/similar?$API_PARAMS").getText("UTF-8")
    def jsonSimilarMovies = new JsonSlurper().parseText(jsonContentSimilarMovies)
    jsonSimilarMovies?.results?.each { Map similarMovie ->
      similarMovie.genre = parseGenres(similarMovie.genre_ids)
      similarMovie.mediatype = "Movie"
      similarMovie.trailerKey = getTrailerForMovie(similarMovie.id)?.key

    }
    return jsonSimilarMovies
  }

  def getExternalLinks(showId){
    def JsonContent = new URL(BASE_URL + "/tv/$showId/external_ids?$API_PARAMS").getText("UTF-8")
    return new JsonSlurper().parseText(JsonContent)
  }

  def getMovieGenres(){
    if(!API_KEY){
      return []
    }
    try{
      def JsonContent = new URL(BASE_URL + "/genre/movie/list?$API_PARAMS").getText("UTF-8")
      def genres =  new JsonSlurper().parseText(JsonContent).genres

      genres?.each{ genre ->
        genre["apiId"] = genre.id
        genre.id = null
      }

      return genres
    }catch (e){
      log.warn("could not load genres this time, " + e.message)
      return []
    }

  }

  def getTvGenres(){
    if(!API_KEY){
      return []
    }
    try{
      def JsonContent = new URL(BASE_URL + "/genre/tv/list?$API_PARAMS").getText("UTF-8")
      def genres =  new JsonSlurper().parseText(JsonContent).genres

      genres?.each{ genre ->
        genre["apiId"] = genre.id
        genre.id = null
      }

      return genres
    }catch (e){
      log.warn("could not load genres this time, " + e.message)
      return []
    }
  }

  def getTrailerForMovie(movieId){
    try{
      def JsonContent = new URL(BASE_URL + "/movie/$movieId/videos?$API_PARAMS").getText("UTF-8")
      def videos =  new JsonSlurper().parseText(JsonContent).results

      def trailer = videos.findAll{it.type == "Trailer"}.max{it.size}
      return trailer
    }
    catch (e){
      log.error("problem during getTrailerForMovie for ${movieId}")
    }
  }

  def getFullMovieMeta(movieId){
    try{
      def JsonContent = new URL(BASE_URL + "/movie/$movieId?$API_PARAMS").getText("UTF-8")
      return new JsonSlurper().parseText(JsonContent)
    }catch (e){
      log.warn("could not load fullMeta for Movie this time, " + e.message)
    }

  }

  def getFullTvShowMeta(tvId){
    try{
      def JsonContent = new URL(BASE_URL + "/tv/$tvId?$API_PARAMS").getText("UTF-8")
      return new JsonSlurper().parseText(JsonContent)
    }catch (e){
      log.warn("could not load fullMeta for TV SHOW this time, " + e.message)
    }
  }

  def getEpisodeMeta(tvApiId, seasonNumber, episodeNumber){
    def requestUrl = BASE_URL + "/tv/$tvApiId/season/$seasonNumber/episode/$episodeNumber?$API_PARAMS"
    URL url = new URL(requestUrl)
    HttpURLConnection conn = url.openConnection()
    if(conn.responseCode != 200){
      throw new Exception("TMDB request failed with statusCode: " + conn?.responseCode + ", responseMessage: " + conn?.responseMessage + ", url: " + requestUrl)
    }
    def JsonContent = url.getText("UTF-8")
    return new JsonSlurper().parseText(JsonContent)
  }

  def searchForEntry(type, name, String year = null) {

    def cachedApiData = apiCacheData."$type:$name"
    if(false && cachedApiData){
      return cachedApiData
    }
    def query = URLEncoder.encode(name, "UTF-8")


    def requestUrl = BASE_URL + '/search/' + type + '?query=' + query + '&api_key=' + API_KEY
    def data
    URL url

    try {
      url = new URL(requestUrl)
      def JsonContent = url.getText("UTF-8")
      data = new JsonSlurper().parseText(JsonContent)
      if(data.results?.size() > 1 && year){
        data.results = data.results.findAll{it.release_date.take(4) == year}
      }
      apiCacheData["$type:$name"] = data
    }
    catch(e) {
      throw new Exception("TMDB request failed", e)
    }

    return data
  }

  def getEntryById(String type, id, data = [:]){
    if(type == 'movie'){
      return getFullMovieMeta(id)
    }
    if(type == 'tv' || type == 'tvShow'){
      return getFullTvShowMeta(id)
    }
    if(type == 'episode' && data){
      def result = getEpisodeMeta(data.tvShowId, data.season, data.episodeNumber)
      result.tv_id = data.tvShowId
      result.season_number = data.season
      result.episode_number = data.episodeNumber
      return result
    }
  }

  @Transactional
  def createEntityFromApiId(type, id, data = [:]){
    def apiData = getEntryById(type, id, data)
    def entity
    try{
      entity = createEntityFromApiData(type, apiData)
    }catch (e){
      log.error("Error occured while trying to retrieve data from TheMovieDB: ${e.message}", e)
    }
    return entity
  }


  def createEntityFromApiData(type, Map data){
    def apiId = data.id
    data.remove('id')
    def entity

    if(type == 'movie'){
      entity = new Movie()
    }
    if(type == 'tv' || type == 'tvShow'){
      entity = new TvShow()
    }
    if(type == 'episode'){
      entity = new Episode()
      TvShow tvShow = TvShow.findByApiIdAndDeletedNotEqual(data.tv_id, true)
      if(!tvShow){
        tvShow = createEntityFromApiId('tv', data.tv_id)
      }
      entity.show = tvShow
//      log.debug("epiosde data")
    }

    entity.properties = data
    if(data.genres){
      entity.genre = parseGenres(data.genres*.id)
    }
    if(type == 'movie'){
      entity.trailerKey = getTrailerForMovie(apiId)?.key
    }
    entity.apiId = apiId
    if(entity instanceof Movie){
      entity.imdb_id = entity.getFullMovieMeta()?.imdb_id
    }
    if(entity instanceof TvShow){
      entity.imdb_id = entity.getExternalLinks()?.imdb_id
    }

    entity.save(flush:true, failOnError:true)
    return entity
  }

  def parseGenres(movieDbGenres){
    def streamaGenres = []
    movieDbGenres.each{ metaGenre ->
      Genre genre = Genre.findByApiId(metaGenre)
      streamaGenres.add(genre)
    }
    return streamaGenres
  }

  def isImageReachable(String imageId){
    URL imageUrl = new URL(buildImagePath(imageId, 300))
    HttpURLConnection connection = (HttpURLConnection) imageUrl.openConnection()
    connection.setRequestMethod("GET")
    connection.connect()
    int code = connection.getResponseCode()
    return (code == 200)
  }

  /**
   * builds entire image path for tmdb image paths. Ie returns something like
   * https://image.tmdb.org/t/p/w300/uZEIHtWmJKzCL59maAgfkpbcGzC.jpg
   * @param propertyName on the video instance
   * @param size for the tmdb image path. defaults to 300
   * @return entire image link for tmdb, for non-tmdb-videos returns value as is.
   */
  static String buildImagePath(String imagePath, Integer size = 300){

    if(imagePath?.startsWith('/')){
      return "https://image.tmdb.org/t/p/w$size$imagePath"
    }else{
      return imagePath
    }
  }

  def refreshData(instance, path){
    if(instance instanceof TvShow){
      Map meta = instance.getFullTvShowMeta()
      if(!meta){
        return
      }
      instance[path] = meta[path]
      instance.save(failOnError: true, flush: true)
    }
  }
}
