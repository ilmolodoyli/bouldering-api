from fastapi import FastAPI, Depends, HTTPException, status # добавление эндпоинт удаление
from sqlalchemy.orm import Session
from passlib.context import CryptContext # добавил: сейф для паролей, чтобы никто не украл чистый текст
import models
import schemas
from database import engine, SessionLocal
models.Base.metadata.create_all(bind=engine)# эта команда при запуске сервера проверяет файл models.py и создает в PostgreSQL все таблицы, которых там еще нет.
app = FastAPI(title="Bouldering Tournament API") # создаем само веб-приложение (сервер). То, что мы тут напишем, будет отображаться в заголовке Swagger-документации.
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto") # добавил: инструмент шифрования пароля
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
        category=competitor.category,
        user_id=competitor.user_id,             # добавил: привязал человека к его аккаунту
        tournament_id=competitor.tournament_id   # добавил: привязал к конкретному соревнованию
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
        age_category=route.age_category,
        tournament_id=route.tournament_id # добавил: трасса теперь знает к какому фестивалю относится
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
    route = db.query(models.Route).filter(models.Route.id == route_id).first()
    if not route:
        raise HTTPException(
            status_code = status.HTTP_404_NOT_FOUND,
            detail = f"Трасса с id {route_id} не найдена"
        )
    db.query(models.Result).filter(models.Result.route_id == route_id).delete()
    db.delete(route)
    db.commit()
    return None
# --- регистрация и управление ролями ---
# добавил: вход в систему, по умолчанию выдаем роль обычного спортсмена
@app.post("/auth/register", response_model=schemas.UserResponse, status_code=status.HTTP_201_CREATED)
def register(user: schemas.UserRegister, db: Session = Depends(get_db)):
    # проверяем свободен ли никнейм
    if db.query(models.User).filter(models.User.username == user.username).first():
        raise HTTPException(status_code=400, detail="Логин уже занят")
    # прячем пароль в хеш
    hashed = pwd_context.hash(user.password)
    db_user = models.User(username=user.username, password_hash=hashed, role=models.UserRole.ATHLETE)
    db.add(db_user)
    db.commit()
    db.refresh(db_user)
    return db_user
# добавил: просмотр всех зарегистрированных людей для организатора
@app.get("/users/", response_model=list[schemas.UserResponse])
def read_users(db: Session = Depends(get_db)):
    return db.query(models.User).all()
# добавил: организатор меняет роль (делает смотрящим или организатором)
@app.patch("/users/{user_id}/role", response_model=schemas.UserResponse)
def update_user_role(user_id: int, role_data: schemas.UserRoleUpdate, db: Session = Depends(get_db)):
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="Пользователь не найден")
    user.role = role_data.role
    db.commit()
    db.refresh(user)
    return user
# управление турнирами
# добавил: создание нового турнира на скалодроме
@app.post("/tournaments/", response_model=schemas.TournamentResponse, status_code=status.HTTP_201_CREATED)
def create_tournament(tournament: schemas.TournamentCreate, db: Session = Depends(get_db)):
    db_tourn = models.Tournament(title=tournament.title, stage=tournament.stage)
    db.add(db_tourn)
    db.commit()
    db.refresh(db_tourn)
    return db_tourn
# добавил: список турниров (прошедшие, текущие и грядущие)
@app.get("/tournaments/", response_model=list[schemas.TournamentResponse])
def read_tournaments(db: Session = Depends(get_db)):
    return db.query(models.Tournament).all()
# добавил: переключение этапа соревнований с квалификации на финал
@app.patch("/tournaments/{tournament_id}/stage", response_model=schemas.TournamentResponse)
def update_tournament_stage(tournament_id: int, stage_data: schemas.TournamentUpdateStage, db: Session = Depends(get_db)):
    tourn = db.query(models.Tournament).filter(models.Tournament.id == tournament_id).first()
    if not tourn:
        raise HTTPException(status_code=404, detail="Турнир не найден")
    tourn.stage = stage_data.stage
    db.commit()
    db.refresh(tourn)
    return tourn