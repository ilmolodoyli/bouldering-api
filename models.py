from sqlalchemy import Column, Integer, String, ForeignKey
from database import Base
# это чертежи моих таблиц для постгреса. алхимия смотрит сюда и сама создает колонки в базе.
# таблица участников
class Competitor(Base):
    __tablename__ = "competitors" # как таблица физически называется в бд
    id = Column(Integer, primary_key=True, index=True) # главный ключ (айдишник)
    full_name = Column(String)
    age = Column(Integer)
    category = Column(String)
# таблица трасс
class Route(Base):
    __tablename__ = "routes"
    
    id = Column(Integer, primary_key=True, index=True)
    difficulty = Column(String) # сложность, типа 6a или 7b
    age_category = Column(String)
# таблица результатов (связывает чела и конкретную трассу)
class Result(Base):
    __tablename__ = "results"
    id = Column(Integer, primary_key=True, index=True)
    # внешние ключи: ссылаюсь на конкретного участника и трассу
    competitor_id = Column(Integer, ForeignKey("competitors.id"))
    route_id = Column(Integer, ForeignKey("routes.id"))
    # счетчики попыток. по дефолту нули
    total_attempt = Column(Integer, default=0)
    zone_attempt = Column(Integer, default=0)
    top_attempt = Column(Integer, default=0)