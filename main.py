from fastapi import FastAPI, Depends, HTTPException, status # добавление эндпоинт удаление
from sqlalchemy.orm import Session
import models
import schemas
from database import engine, SessionLocal
models.Base.metadata.create_all(bind=engine)# эта команда при запуске сервера проверяет файл models.py и создает в PostgreSQL все таблицы, которых там еще нет.
app = FastAPI(title="Bouldering Tournament API") # создаем само веб-приложение (сервер). То, что мы тут напишем, будет отображаться в заголовке Swagger-документации.
def get_db(): # эта функция работает как "швейцар" для базы данных. 
    db = SessionLocal() 
    try:
        yield db # На каждый запрос от фронтенда она открывает соединение (yield db)
    finally:
        db.close() # а когда запрос отработал — ГАРАНТИРОВАННО закрывает его (finally: db.close()), 
# чтобы сервер не завис от перегрузки открытых соединений.

# корневой маршрут
@app.get("/")
def read_root():
    # просто проверка, что сервер жив. Возвращает текст в браузере.
    return {"message": "Сервер турнира по скалолазанию успешно запущен"}
# участники
# добавление нового участника в БД
@app.post("/competitors/", response_model=schemas.CompetitorResponse)
def create_competitor(competitor: schemas.CompetitorCreate, db: Session = Depends(get_db)):
    # распаковка JSON от фронтенда и собирает объект для базы
    db_competitor = models.Competitor(
        full_name=competitor.full_name,
        age=competitor.age,
        category=competitor.category
    )
    # добавляю объект в текущую транзакцию (готовим к отправке)
    db.add(db_competitor)
    # физически сохраняем изменения в PostgreSQL
    db.commit()
    # обновляем объект, чтобы PostgreSQL подтянула сгенерированный ID
    db.refresh(db_competitor)
    # возвращаем фронтенду готовые данные с новым ID
    return db_competitor
# получение списка всех участников
@app.get("/competitors/", response_model=list[schemas.CompetitorResponse])
def read_competitors(skip: int=0, limit: int = 100, db: Session = Depends(get_db)):
    # запрашиваю из таблицы Competitor записи. 
    # offset и limit нужны для пагинации (например, выдавать по 100 человек на страницу)
    competitors = db.query(models.Competitor).offset(skip).limit(limit).all()
    return competitors
# трассы
# добавление новой трассы
@app.post("/routes/", response_model=schemas.RouteResponse)
def create_route(route: schemas.RouteCreate, db: Session = Depends(get_db)):
    db_route = models.Route(
        difficulty=route.difficulty,
        age_category=route.age_category
    )
    db.add(db_route)
    db.commit()
    db.refresh(db_route)
    return db_route
# получение списка всех трасс
@app.get("/routes/", response_model=list[schemas.RouteResponse])
def read_routes(skip: int = 0, limit: int = 100, db: Session = Depends(get_db)):
    routes = db.query(models.Route).offset(skip).limit(limit).all()
    return routes 
# получение списков всех результатов
@app.get("/results/", response_model=list[schemas.ResultResponse])
def read_results(skip: int = 0, limit: int = 100, db: Session = Depends(get_db)):
    return db.query(models.Result).offset(skip).limit(limit).all()
# резултат соревнований
# главный маршрут для судьи, сохранение или обновление попыток
@app.post("/results/", response_model=schemas.ResultResponse)
def save_result(result: schemas.ResultCreate, db: Session = Depends(get_db)):
    # сначала сделаю поиск: лез ли уже этот участник (competitor_id) эту трассу (route_id)?
    db_result = db.query(models.Result).filter(
        models.Result.competitor_id == result.competitor_id,
        models.Result.route_id == result.route_id
    ).first()
    if db_result:
        # если запись найдена (уже лез) — мы не создаем дубликат, а просто обновляем цифры.
        # это сработает, если судья вносит вторую попытку или исправляет ошибку.
        db_result.total_attempt = result.total_attempt
        db_result.zone_attempt = result.zone_attempt
        db_result.top_attempt = result.top_attempt
    else:
        # если записи нет (лезет впервые) — создаем совершенно новую запись в таблице Result.
        db_result = models.Result(
            competitor_id=result.competitor_id,
            route_id=result.route_id,
            total_attempt=result.total_attempt,
            zone_attempt=result.zone_attempt,
            top_attempt=result.top_attempt
        )
        db.add(db_result)
    # сохраняем изменения (неважно, обновили мы старую запись или создали новую)
    db.commit()
    db.refresh(db_result)
    # возвращаем обновленный результат фронтенду, чтобы интерфейс судьи знал, что всё ОК
    return db_result
# удаление участников : Апгрейд
@app.delete("/competitors/{competitor_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_competitor(competitor_id: int, db: Session = Depends(get_db)):
    competitor = db.query(models.Competitor).filter(models.Competitor.id == competitor_id).first()
    if not competitor:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail = f"Участник с id {competitor_id} не найден"
        )
    db.query(models.Result).filter(models.Result.competitor_id == competitor_id).delete()
    db.delete(competitor)
    db.commit()
    return None
# удаление трассы 
@app.delete("/routes/{route_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_route(route_id: int, db: Session = Depends(get_db)):
    route = db.query(models.Route).folter(models.Route.id == route_id).first()
    if not route:
        raise HTTPException(
            status_code = status.HTTP_404_NOT_FOUND,
            detail = f"Трасса с id {route_id} не найдена"
        )
    db.query(models.Result).filter(models.Result.route_id == route_id).delete()
    db.delete(route)
    db.commit()
    return None